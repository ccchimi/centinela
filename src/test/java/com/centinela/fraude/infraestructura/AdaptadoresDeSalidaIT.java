package com.centinela.fraude.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.centinela.fraude.dominio.excepcion.ConflictoDeVersion;
import com.centinela.fraude.dominio.modelo.Decision;
import com.centinela.fraude.dominio.modelo.EvaluacionDeFraude;
import com.centinela.fraude.dominio.modelo.PerfilDeComportamiento;
import com.centinela.fraude.dominio.modelo.SenalDeRiesgo;
import com.centinela.fraude.dominio.modelo.vo.ClaveDeVelocidad;
import com.centinela.fraude.dominio.modelo.vo.DimensionDeVelocidad;
import com.centinela.fraude.dominio.modelo.vo.Dinero;
import com.centinela.fraude.dominio.modelo.vo.Geolocalizacion;
import com.centinela.fraude.dominio.modelo.vo.IdCliente;
import com.centinela.fraude.dominio.modelo.vo.IdDispositivo;
import com.centinela.fraude.dominio.modelo.vo.IdTransaccion;
import com.centinela.fraude.dominio.puerto.salida.ActaDeDecisiones;
import com.centinela.fraude.dominio.puerto.salida.BandejaDeSalida;
import com.centinela.fraude.dominio.puerto.salida.ContadorDeVelocidad;
import com.centinela.fraude.dominio.puerto.salida.RepositorioDePerfiles;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Los adaptadores de salida contra sus servicios reales.
 *
 * <p>Comprueba lo que un test con mocks no puede: que el mapeo sobrevive a una ida y vuelta por
 * DynamoDB, que el bloqueo optimista de verdad rechaza la segunda escritura, que la escritura
 * transaccional del outbox deja las dos filas o ninguna, y que el contador de Redis expira solo.
 */
@DisplayName("Adaptadores de salida")
class AdaptadoresDeSalidaIT extends PruebaDeIntegracion {

    private static final Instant AHORA = Instant.parse("2026-08-24T12:00:00Z");

    @Autowired
    private RepositorioDePerfiles perfiles;

    @Autowired
    private ActaDeDecisiones acta;

    @Autowired
    private BandejaDeSalida bandeja;

    @Autowired
    private ContadorDeVelocidad contadores;

    @Nested
    @DisplayName("RepositorioDePerfiles sobre DynamoDB")
    class Perfiles {

        @Test
        @DisplayName("el perfil sobrevive intacto a una ida y vuelta")
        void idaYVuelta() {
            IdCliente cliente = IdCliente.de("cli-" + UUID.randomUUID());
            PerfilDeComportamiento original = perfilDe(cliente, AHORA, 1L);

            perfiles.guardar(original);

            assertThat(perfiles.buscarPor(cliente)).contains(original);
        }

        @Test
        @DisplayName("un cliente sin perfil devuelve vacío, no un error")
        void clienteDesconocido() {
            assertThat(perfiles.buscarPor(IdCliente.de("cli-inexistente-" + UUID.randomUUID())))
                    .isEmpty();
        }

        @Test
        @DisplayName("el bloqueo optimista rechaza una escritura calculada sobre una base vieja")
        void bloqueoOptimista() {
            // El escenario real: dos evaluaciones concurrentes del mismo cliente leen la versión
            // 3 y ambas calculan la 4. La primera gana; la segunda tiene que chocar, no
            // sobrescribir en silencio el aprendizaje de la primera.
            IdCliente cliente = IdCliente.de("cli-" + UUID.randomUUID());
            perfiles.guardar(perfilDe(cliente, AHORA.minusSeconds(120), 3L));

            PerfilDeComportamiento primera = perfilDe(cliente, AHORA, 4L);
            PerfilDeComportamiento segunda = perfilDe(cliente, AHORA.plusSeconds(1), 4L);

            perfiles.guardar(primera);

            assertThatThrownBy(() -> perfiles.guardar(segunda))
                    .isInstanceOf(ConflictoDeVersion.class)
                    .satisfies(e -> assertThat(((ConflictoDeVersion) e).versionEsperada()).isEqualTo(3L));

            assertThat(perfiles.buscarPor(cliente))
                    .get()
                    .extracting(PerfilDeComportamiento::actualizadoEn)
                    .isEqualTo(AHORA);
        }

        @Test
        @DisplayName("un perfil sin posición previa se guarda y se lee sin inventarse datos")
        void opcionalesAusentes() {
            IdCliente cliente = IdCliente.de("cli-" + UUID.randomUUID());
            PerfilDeComportamiento sinHistorial =
                    PerfilDeComportamiento.nuevoPara(cliente, Currency.getInstance("EUR"), AHORA);

            perfiles.guardar(sinHistorial);

            assertThat(perfiles.buscarPor(cliente))
                    .get()
                    .satisfies(perfil -> {
                        assertThat(perfil.ultimaPosicion()).isEmpty();
                        assertThat(perfil.ultimaActividad()).isEmpty();
                        assertThat(perfil.paisesHabituales()).isEmpty();
                        assertThat(perfil.esFiable()).isFalse();
                        assertThat(perfil.version()).isZero();
                    });
        }
    }

    @Nested
    @DisplayName("ActaDeDecisiones y bandeja de salida")
    class Acta {

        @Test
        @DisplayName("asentar deja la auditoría y la entrada pendiente en la misma transacción")
        void asientoAtomico() {
            EvaluacionDeFraude evaluacion = evaluacionDePrueba();

            acta.asentar(evaluacion);

            assertThat(acta.buscarPor(evaluacion.transaccion())).contains(evaluacion);
            assertThat(pendientesDe(evaluacion.transaccion()))
                    .as("la decisión debería estar esperando publicación")
                    .isPresent();
        }

        @Test
        @DisplayName("la evaluación se reconstruye con sus señales y advertencias")
        void idaYVuelta() {
            EvaluacionDeFraude original = EvaluacionDeFraude.apartirDe(
                    IdTransaccion.de("tx-" + UUID.randomUUID()),
                    List.of(
                            SenalDeRiesgo.de("VIAJE_IMPOSIBLE", "Desplazamiento no factible", 400),
                            SenalDeRiesgo.de("PAIS_INUSUAL", "Origen en JP", 120)),
                    List.of("RAFAGA_POR_CLIENTE: sin datos suficientes (contador no disponible)"),
                    AHORA,
                    Duration.ofMillis(37));

            acta.asentar(original);

            assertThat(acta.buscarPor(original.transaccion())).contains(original);
        }

        @Test
        @DisplayName("asentar dos veces la misma decisión no la duplica")
        void esIdempotente() {
            // Con entrega al-menos-una-vez, reprocesar el mismo mensaje tras un reinicio tiene
            // que ser inocuo.
            EvaluacionDeFraude evaluacion = evaluacionDePrueba();

            acta.asentar(evaluacion);
            acta.asentar(evaluacion);

            assertThat(acta.buscarPor(evaluacion.transaccion())).contains(evaluacion);
            assertThat(acta.buscarPor(evaluacion.transaccion()).orElseThrow().decision())
                    .isEqualTo(Decision.APROBAR);
        }

        @Test
        @DisplayName("marcar publicada la saca del índice de pendientes")
        void marcarPublicadaLaRetira() {
            EvaluacionDeFraude evaluacion = evaluacionDePrueba();
            acta.asentar(evaluacion);
            assertThat(pendientesDe(evaluacion.transaccion())).isPresent();

            bandeja.marcarPublicada(evaluacion);

            // Lo que se comprueba aquí es que el índice es realmente disperso: al eliminar el
            // atributo de estado la entrada desaparece del índice, no se queda dentro con otro
            // valor. Si se quedara, el coste del relevo crecería con todo el histórico.
            assertThat(pendientesDe(evaluacion.transaccion())).isEmpty();
        }

        @Test
        @DisplayName("un intento fallido incrementa el contador sin sacarla de pendientes")
        void intentoFallidoNoLaRetira() {
            EvaluacionDeFraude evaluacion = evaluacionDePrueba();
            acta.asentar(evaluacion);

            bandeja.registrarIntentoFallido(evaluacion, "broker no disponible");
            bandeja.registrarIntentoFallido(evaluacion, "broker no disponible");

            assertThat(pendientesDe(evaluacion.transaccion()))
                    .get()
                    .extracting(BandejaDeSalida.Pendiente::intentos)
                    .isEqualTo(2);
        }

        private Optional<BandejaDeSalida.Pendiente> pendientesDe(IdTransaccion transaccion) {
            return bandeja.pendientes(200).stream()
                    .filter(p -> p.evaluacion().transaccion().equals(transaccion))
                    .findFirst();
        }

        private EvaluacionDeFraude evaluacionDePrueba() {
            return EvaluacionDeFraude.apartirDe(
                    IdTransaccion.de("tx-" + UUID.randomUUID()), List.of(), AHORA, Duration.ofMillis(5));
        }
    }

    @Nested
    @DisplayName("ContadorDeVelocidad sobre Redis")
    class Contadores {

        @Test
        @DisplayName("cuenta los incrementos dentro de la ventana")
        void cuentaIncrementos() {
            ClaveDeVelocidad clave = claveNueva();

            assertThat(contadores.incrementarYContar(clave, Duration.ofMinutes(1))).isEqualTo(1);
            assertThat(contadores.incrementarYContar(clave, Duration.ofMinutes(1))).isEqualTo(2);
            assertThat(contadores.contar(clave, Duration.ofMinutes(1))).isEqualTo(2);
        }

        @Test
        @DisplayName("una clave nunca vista cuenta cero sin crearla")
        void claveInexistente() {
            assertThat(contadores.contar(claveNueva(), Duration.ofMinutes(1))).isZero();
        }

        @Test
        @DisplayName("el primer incremento fija la expiración, y no la renueva después")
        void laVentanaEsFija() throws InterruptedException {
            // La ventana es fija a propósito (ver el javadoc del adaptador). Este test fija esa
            // decisión: si alguien cambiara el script para renovar el TTL en cada incremento, la
            // ventana pasaría a ser deslizante-por-inactividad y el contador no bajaría nunca
            // durante una ráfaga sostenida.
            ClaveDeVelocidad clave = claveNueva();

            contadores.incrementarYContar(clave, Duration.ofMillis(600));
            Thread.sleep(200);
            contadores.incrementarYContar(clave, Duration.ofMillis(600));
            assertThat(contadores.contar(clave, Duration.ofMillis(600))).isEqualTo(2);

            Thread.sleep(600);
            assertThat(contadores.contar(clave, Duration.ofMillis(600)))
                    .as("la clave debería haber expirado 600 ms después del PRIMER incremento")
                    .isZero();
        }

        @Test
        @DisplayName("los incrementos concurrentes no pierden cuentas")
        void esAtomicoBajoConcurrencia() {
            // Si el adaptador hiciera INCR y EXPIRE por separado, o un GET seguido de SET, aquí
            // se perderían actualizaciones. El script Lua lo hace en una sola operación atómica.
            ClaveDeVelocidad clave = claveNueva();
            int hilos = 50;

            try (var ejecutor = Executors.newVirtualThreadPerTaskExecutor()) {
                var resultados = ejecutor.invokeAll(java.util.Collections.nCopies(
                        hilos, () -> contadores.incrementarYContar(clave, Duration.ofMinutes(1))));
                assertThat(resultados).hasSize(hilos);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }

            assertThat(contadores.contar(clave, Duration.ofMinutes(1))).isEqualTo(hilos);
        }

        private ClaveDeVelocidad claveNueva() {
            return ClaveDeVelocidad.de(DimensionDeVelocidad.TRANSACCIONES_POR_CLIENTE, "cli-" + UUID.randomUUID());
        }
    }

    private static PerfilDeComportamiento perfilDe(IdCliente cliente, Instant actualizadoEn, long version) {
        return new PerfilDeComportamiento(
                cliente,
                Dinero.de("100.00", "EUR"),
                Dinero.de("500.00", "EUR"),
                Set.of("ES", "PT"),
                Set.of("com-100", "com-200"),
                Set.of(IdDispositivo.de("disp-1"), IdDispositivo.de("disp-2")),
                250L,
                Optional.of(AHORA.minusSeconds(3600)),
                Optional.of(Geolocalizacion.de(40.4168, -3.7038, "ES")),
                actualizadoEn,
                version);
    }
}
