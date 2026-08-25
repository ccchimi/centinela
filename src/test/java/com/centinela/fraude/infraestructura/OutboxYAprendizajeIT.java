package com.centinela.fraude.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;

import com.centinela.fraude.aplicacion.servicio.RelevoDeBandejaDeSalida;
import com.centinela.fraude.dominio.modelo.PerfilDeComportamiento;
import com.centinela.fraude.dominio.modelo.vo.IdCliente;
import com.centinela.fraude.dominio.modelo.vo.IdTransaccion;
import com.centinela.fraude.dominio.puerto.salida.ActaDeDecisiones;
import com.centinela.fraude.dominio.puerto.salida.BandejaDeSalida;
import com.centinela.fraude.dominio.puerto.salida.RepositorioDePerfiles;
import java.time.Instant;
import java.util.UUID;
import org.awaitility.Awaitility;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Las dos piezas que cierran los huecos que quedaban abiertos: el outbox y la realimentación del
 * perfil, verificadas contra Kafka, LocalStack y Redis reales.
 *
 * <p>Lo que se comprueba aquí no se puede comprobar con mocks: que la decisión aparece en la
 * bandeja como efecto de la evaluación, que el relevo la saca de verdad y la deja en Kafka, y
 * que el perfil del cliente crece solo entre una transacción y la siguiente.
 */
@DisplayName("Outbox y aprendizaje del perfil")
class OutboxYAprendizajeIT extends PruebaDeIntegracion {

    private static final Instant AHORA = Instant.parse("2026-08-24T12:00:00Z");

    @Autowired
    private ActaDeDecisiones acta;

    @Autowired
    private BandejaDeSalida bandeja;

    @Autowired
    private RepositorioDePerfiles perfiles;

    @Autowired
    private RelevoDeBandejaDeSalida relevo;

    @Test
    @DisplayName("la decisión pasa por la bandeja antes de llegar a Kafka")
    void laDecisionPasaPorLaBandeja() {
        // El caso de uso ya no publica: asienta. Que el mensaje acabe en el topic es obra del
        // relevo, y eso es justo lo que hace que una caída del broker no pierda la decisión.
        String cliente = "cli-" + UUID.randomUUID();
        String transaccion = "tx-" + UUID.randomUUID();

        publicar(
                propiedadesDeKafka.topics().transacciones(),
                cliente,
                TransaccionesJson.compraWeb(
                        transaccion, cliente, "80.00", "com-100", "5942", "ES",
                        40.4168, -3.7038, "ES", "disp-" + UUID.randomUUID(), AHORA.toString()));

        // El asiento aparece en la auditoría...
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(acta.buscarPor(IdTransaccion.de(transaccion)))
                        .isPresent());

        // ...y la decisión termina en Kafka porque el relevo la publica.
        var registro = esperarRegistro(propiedadesDeKafka.topics().decisiones(), r -> transaccion.equals(r.key()));
        assertThat(registro.value()).contains(transaccion);

        // Y una vez publicada, deja de estar pendiente: el índice disperso la ha soltado.
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(pendiente(transaccion))
                        .as("la entrada debería haber salido de la bandeja")
                        .isFalse());
    }

    @Test
    @DisplayName("una entrada asentada a mano la publica el relevo en su siguiente ciclo")
    void elRelevoPublicaLoPendiente() {
        // Sin pasar por Kafka de entrada: se asienta directamente y se invoca un ciclo del
        // relevo. El resultado del ciclo es un valor, así que se puede afirmar sin sondear.
        var evaluacion = com.centinela.fraude.dominio.modelo.EvaluacionDeFraude.apartirDe(
                IdTransaccion.de("tx-manual-" + UUID.randomUUID()),
                java.util.List.of(),
                AHORA,
                Duration.ofMillis(4));

        acta.asentar(evaluacion);
        assertThat(pendiente(evaluacion.transaccion().valor())).isTrue();

        // El planificador puede haberla publicado ya; se comprueba el estado final, no el
        // recuento del ciclo, para que el test no dependa de quién llegó antes.
        relevo.ejecutarCiclo();

        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(pendiente(evaluacion.transaccion().valor()))
                        .isFalse());

        var registro = esperarRegistro(
                propiedadesDeKafka.topics().decisiones(),
                r -> evaluacion.transaccion().valor().equals(r.key()));
        assertThat(registro.value()).contains(evaluacion.transaccion().valor());
    }

    @Test
    @DisplayName("el perfil del cliente crece solo entre una transacción y la siguiente")
    void elPerfilAprendeSolo() {
        String cliente = "cli-aprende-" + UUID.randomUUID();
        IdCliente id = IdCliente.de(cliente);

        // Cliente nuevo: no hay perfil todavía.
        assertThat(perfiles.buscarPor(id)).isEmpty();

        publicar(
                propiedadesDeKafka.topics().transacciones(),
                cliente,
                TransaccionesJson.compraWeb(
                        "tx-" + UUID.randomUUID(), cliente, "60.00", "com-100", "5942", "ES",
                        40.4168, -3.7038, "ES", "disp-aprende", AHORA.toString()));

        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(perfiles.buscarPor(id))
                        .get()
                        .satisfies(perfil -> {
                            assertThat(perfil.transaccionesObservadas()).isEqualTo(1);
                            assertThat(perfil.version()).isEqualTo(1);
                            assertThat(perfil.paisesHabituales()).containsExactly("ES");
                            assertThat(perfil.comerciosHabituales()).containsExactly("com-100");
                            assertThat(perfil.importeMedio().importe()).isEqualByComparingTo("60.00");
                        }));

        // Segunda operación: la media se recalcula y la versión avanza otra vez.
        publicar(
                propiedadesDeKafka.topics().transacciones(),
                cliente,
                TransaccionesJson.compraWeb(
                        "tx-" + UUID.randomUUID(), cliente, "100.00", "com-200", "5942", "ES",
                        40.4168, -3.7038, "ES", "disp-aprende", AHORA.toString()));

        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(perfiles.buscarPor(id))
                        .get()
                        .satisfies(perfil -> {
                            assertThat(perfil.transaccionesObservadas()).isEqualTo(2);
                            assertThat(perfil.version()).isEqualTo(2);
                            assertThat(perfil.importeMedio().importe()).isEqualByComparingTo("80.00");
                            assertThat(perfil.comerciosHabituales()).containsExactlyInAnyOrder("com-100", "com-200");
                        }));
    }

    @Test
    @DisplayName("una transacción rechazada no deja rastro en el perfil")
    void loRechazadoNoEnsena() {
        // La guarda contra el envenenamiento del perfil, comprobada de punta a punta: si un
        // rechazo desde Tokio hiciera habitual a Japón, el atacante entrenaría el motor.
        String cliente = "cli-rechazo-" + UUID.randomUUID();
        IdCliente id = IdCliente.de(cliente);

        perfiles.guardar(new PerfilDeComportamiento(
                id,
                com.centinela.fraude.dominio.modelo.vo.Dinero.de("100.00", "EUR"),
                com.centinela.fraude.dominio.modelo.vo.Dinero.de("500.00", "EUR"),
                java.util.Set.of("ES"),
                java.util.Set.of("com-100"),
                java.util.Set.of(com.centinela.fraude.dominio.modelo.vo.IdDispositivo.de("disp-conocido")),
                250L,
                java.util.Optional.of(AHORA.minusSeconds(3600)),
                java.util.Optional.of(com.centinela.fraude.dominio.modelo.vo.Geolocalizacion.de(40.4168, -3.7038, "ES")),
                AHORA.minusSeconds(3600),
                0L));

        String transaccion = "tx-" + UUID.randomUUID();
        publicar(
                propiedadesDeKafka.topics().transacciones(),
                cliente,
                TransaccionesJson.compraWeb(
                        transaccion, cliente, "3000.00", "com-999", "7995", "MT",
                        35.6762, 139.6503, "JP", "disp-nunca-visto-" + UUID.randomUUID(), AHORA.toString()));

        // Se espera a que la decisión exista para saber que la evaluación terminó.
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(acta.buscarPor(IdTransaccion.de(transaccion)))
                        .get()
                        .satisfies(e -> assertThat(e.bloqueada()).isTrue()));

        assertThat(perfiles.buscarPor(id))
                .get()
                .satisfies(perfil -> {
                    assertThat(perfil.version()).as("el perfil no debería haberse tocado").isZero();
                    assertThat(perfil.paisesHabituales()).containsExactly("ES");
                    assertThat(perfil.transaccionesObservadas()).isEqualTo(250);
                });
    }

    private boolean pendiente(String idTransaccion) {
        return bandeja.pendientes(500).stream()
                .anyMatch(p -> p.evaluacion().transaccion().valor().equals(idTransaccion));
    }
}
