package com.centinela.fraude.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.centinela.fraude.dominio.puerto.salida.ContadorDeVelocidad;
import com.centinela.fraude.dominio.puerto.salida.RegistroDeAuditoria;
import com.centinela.fraude.dominio.puerto.salida.RepositorioDePerfiles;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Los adaptadores de salida contra sus servicios reales.
 *
 * <p>Comprueba lo que un test con mocks no puede: que el mapeo sobrevive a una ida y vuelta por
 * DynamoDB, que la escritura condicionada realmente evita que dos escrituras concurrentes se
 * pisen, y que el contador de Redis expira solo.
 */
@DisplayName("Adaptadores de salida")
class AdaptadoresDeSalidaIT extends PruebaDeIntegracion {

    private static final Instant AHORA = Instant.parse("2026-08-24T12:00:00Z");

    @Autowired
    private RepositorioDePerfiles perfiles;

    @Autowired
    private RegistroDeAuditoria auditoria;

    @Autowired
    private ContadorDeVelocidad contadores;

    @Nested
    @DisplayName("RepositorioDePerfiles sobre DynamoDB")
    class Perfiles {

        @Test
        @DisplayName("el perfil sobrevive intacto a una ida y vuelta")
        void idaYVuelta() {
            IdCliente cliente = IdCliente.de("cli-" + UUID.randomUUID());
            PerfilDeComportamiento original = perfilDe(cliente, AHORA);

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
        @DisplayName("una escritura más antigua no pisa a una más reciente")
        void laEscrituraCondicionadaProtegeLosAgregados() {
            // El escenario real: dos evaluaciones concurrentes del mismo cliente calculan su
            // perfil desde la misma base. Sin la condición, la que termina última gana aunque
            // sus datos sean más viejos, y los agregados retroceden.
            IdCliente cliente = IdCliente.de("cli-" + UUID.randomUUID());
            PerfilDeComportamiento reciente = perfilDe(cliente, AHORA);
            PerfilDeComportamiento antiguo = perfilDe(cliente, AHORA.minusSeconds(60));

            perfiles.guardar(reciente);
            perfiles.guardar(antiguo); // se descarta en silencio: es lo correcto

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
                    PerfilDeComportamiento.nuevoPara(cliente, java.util.Currency.getInstance("EUR"), AHORA);

            perfiles.guardar(sinHistorial);

            assertThat(perfiles.buscarPor(cliente))
                    .get()
                    .satisfies(perfil -> {
                        assertThat(perfil.ultimaPosicion()).isEmpty();
                        assertThat(perfil.ultimaActividad()).isEmpty();
                        assertThat(perfil.paisesHabituales()).isEmpty();
                        assertThat(perfil.esFiable()).isFalse();
                    });
        }
    }

    @Nested
    @DisplayName("RegistroDeAuditoria sobre DynamoDB")
    class Auditoria {

        @Test
        @DisplayName("la evaluación se reconstruye con sus señales y advertencias")
        void idaYVuelta() {
            IdTransaccion transaccion = IdTransaccion.de("tx-" + UUID.randomUUID());
            EvaluacionDeFraude original = EvaluacionDeFraude.apartirDe(
                    transaccion,
                    List.of(
                            SenalDeRiesgo.de("VIAJE_IMPOSIBLE", "Desplazamiento no factible", 400),
                            SenalDeRiesgo.de("PAIS_INUSUAL", "Origen en JP", 120)),
                    List.of("RAFAGA_POR_CLIENTE: sin datos suficientes (contador no disponible)"),
                    AHORA,
                    Duration.ofMillis(37));

            auditoria.registrar(original);

            assertThat(auditoria.buscarPor(transaccion)).contains(original);
        }

        @Test
        @DisplayName("registrar dos veces la misma decisión no la duplica")
        void esIdempotente() {
            // Con entrega al-menos-una-vez, reprocesar el mismo mensaje tras un reinicio tiene
            // que ser inocuo.
            IdTransaccion transaccion = IdTransaccion.de("tx-" + UUID.randomUUID());
            EvaluacionDeFraude evaluacion =
                    EvaluacionDeFraude.apartirDe(transaccion, List.of(), AHORA, Duration.ofMillis(5));

            auditoria.registrar(evaluacion);
            auditoria.registrar(evaluacion);

            assertThat(auditoria.buscarPor(transaccion)).contains(evaluacion);
            assertThat(auditoria.buscarPor(transaccion).orElseThrow().decision()).isEqualTo(Decision.APROBAR);
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
                List<Future<Long>> resultados = ejecutor.invokeAll(java.util.Collections.nCopies(
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

    private static PerfilDeComportamiento perfilDe(IdCliente cliente, Instant actualizadoEn) {
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
                actualizadoEn);
    }
}
