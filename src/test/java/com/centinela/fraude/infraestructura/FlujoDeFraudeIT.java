package com.centinela.fraude.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;

import com.centinela.fraude.dominio.modelo.Decision;
import com.centinela.fraude.dominio.modelo.EvaluacionDeFraude;
import com.centinela.fraude.dominio.modelo.PerfilDeComportamiento;
import com.centinela.fraude.dominio.modelo.vo.Dinero;
import com.centinela.fraude.dominio.modelo.vo.Geolocalizacion;
import com.centinela.fraude.dominio.modelo.vo.IdCliente;
import com.centinela.fraude.dominio.modelo.vo.IdDispositivo;
import com.centinela.fraude.dominio.modelo.vo.IdTransaccion;
import com.centinela.fraude.dominio.puerto.salida.RegistroDeAuditoria;
import com.centinela.fraude.dominio.puerto.salida.RepositorioDePerfiles;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Flujo completo: mensaje de transacción en Kafka, evaluación contra el perfil de DynamoDB y los
 * contadores de Redis, y decisión publicada y auditada.
 *
 * <p>Sin un solo mock. Lo que se prueba aquí no es la lógica de las reglas —eso ya está cubierto
 * con tests unitarios en microsegundos— sino que las piezas encajan: que el JSON del emisor se
 * deserializa, que el traductor construye el dominio, que los adaptadores hablan con sus
 * servicios y que la decisión sale por donde debe.
 */
@DisplayName("Flujo de fraude de punta a punta")
class FlujoDeFraudeIT extends PruebaDeIntegracion {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant AHORA = Instant.parse("2026-08-24T12:00:00Z");

    @Autowired
    private RepositorioDePerfiles perfiles;

    @Autowired
    private RegistroDeAuditoria auditoria;

    @Test
    @DisplayName("una compra habitual de un cliente conocido se aprueba y queda auditada")
    void compraHabitualSeAprueba() {
        String cliente = "cli-" + UUID.randomUUID();
        String transaccion = "tx-" + UUID.randomUUID();
        String dispositivo = "disp-" + UUID.randomUUID();
        sembrarPerfilConocido(cliente, dispositivo);

        publicar(
                propiedadesDeKafka.topics().transacciones(),
                cliente,
                TransaccionesJson.compraWeb(
                        transaccion, cliente, "120.00", "com-100", "5942", "ES",
                        40.4168, -3.7038, "ES", dispositivo, AHORA.toString()));

        JsonNode decision = esperarDecision(transaccion);

        assertThat(decision.get("decision").asString()).isEqualTo(Decision.APROBAR.name());
        assertThat(decision.get("puntaje").asInt()).isZero();
        assertThat(decision.get("degradada").asBoolean()).isFalse();
        assertThat(decision.get("advertencias")).isEmpty();
        assertThat(decision.get("versionDePolitica").asString()).isEqualTo("v1-inicial");

        // Y el rastro de auditoría existe con el mismo veredicto.
        Optional<EvaluacionDeFraude> auditada = auditoria.buscarPor(IdTransaccion.de(transaccion));
        assertThat(auditada).isPresent();
        assertThat(auditada.orElseThrow().decision()).isEqualTo(Decision.APROBAR);
        assertThat(auditada.orElseThrow().latencia()).isPositive();
    }

    @Test
    @DisplayName("una toma de cuenta acumula señales y se rechaza")
    void tomaDeCuentaSeRechaza() {
        String cliente = "cli-" + UUID.randomUUID();
        String transaccion = "tx-" + UUID.randomUUID();
        String dispositivo = "disp-" + UUID.randomUUID();
        sembrarPerfilConocido(cliente, dispositivo);

        // Desde Tokio una hora después de operar en Madrid, con un dispositivo nuevo, por 30
        // veces el gasto habitual y en un comercio de categoría sensible.
        publicar(
                propiedadesDeKafka.topics().transacciones(),
                cliente,
                TransaccionesJson.compraWeb(
                        transaccion, cliente, "3000.00", "com-999", "7995", "MT",
                        35.6762, 139.6503, "JP", "disp-nunca-visto-" + UUID.randomUUID(), AHORA.toString()));

        JsonNode decision = esperarDecision(transaccion);

        assertThat(decision.get("decision").asString()).isEqualTo(Decision.RECHAZAR.name());
        assertThat(decision.get("nivel").asString()).isEqualTo("CRITICO");

        var codigos = decision.get("senales").valueStream().map(s -> s.get("codigo").asString()).toList();
        assertThat(codigos)
                .contains(
                        "VIAJE_IMPOSIBLE",
                        "DISPOSITIVO_DESCONOCIDO",
                        "PAIS_INUSUAL",
                        "DESVIACION_DE_IMPORTE",
                        "COMERCIO_ALTO_RIESGO");

        assertThat(auditoria.buscarPor(IdTransaccion.de(transaccion)))
                .get()
                .satisfies(evaluacion -> assertThat(evaluacion.bloqueada()).isTrue());
    }

    @Test
    @DisplayName("un cliente sin perfil se aprueba pero la evaluación sale marcada como degradada")
    void clienteSinPerfilProduceAdvertencias() {
        // Es el caso que separa un motor honesto de uno que aprueba a ciegas: sin historial, las
        // reglas de comportamiento no pueden decidir, y eso tiene que verse en la decisión.
        String cliente = "cli-nuevo-" + UUID.randomUUID();
        String transaccion = "tx-" + UUID.randomUUID();

        publicar(
                propiedadesDeKafka.topics().transacciones(),
                cliente,
                TransaccionesJson.compraWeb(
                        transaccion, cliente, "120.00", "com-100", "5942", "ES",
                        40.4168, -3.7038, "ES", "disp-" + UUID.randomUUID(), AHORA.toString()));

        JsonNode decision = esperarDecision(transaccion);

        assertThat(decision.get("decision").asString()).isEqualTo(Decision.APROBAR.name());
        assertThat(decision.get("degradada").asBoolean()).isTrue();

        var advertencias = decision.get("advertencias").valueStream().map(JsonNode::asString).toList();
        assertThat(advertencias)
                .anySatisfy(a -> assertThat(a).contains("DESVIACION_DE_IMPORTE"))
                .anySatisfy(a -> assertThat(a).contains("DISPOSITIVO_DESCONOCIDO"))
                .anySatisfy(a -> assertThat(a).contains("PAIS_INUSUAL"));

        // Las reglas de ráfaga NO se degradan: Redis sí respondió.
        assertThat(advertencias).noneSatisfy(a -> assertThat(a).contains("RAFAGA"));
    }

    @Test
    @DisplayName("una transacción sin huella de dispositivo degrada esa regla y sólo esa")
    void faltaDeHuellaDegradaUnaRegla() {
        String cliente = "cli-" + UUID.randomUUID();
        String transaccion = "tx-" + UUID.randomUUID();
        String dispositivo = "disp-" + UUID.randomUUID();
        sembrarPerfilConocido(cliente, dispositivo);

        publicar(
                propiedadesDeKafka.topics().transacciones(),
                cliente,
                TransaccionesJson.compraWebSinDispositivo(transaccion, cliente, "120.00", AHORA.toString()));

        JsonNode decision = esperarDecision(transaccion);

        var advertencias = decision.get("advertencias").valueStream().map(JsonNode::asString).toList();
        assertThat(advertencias).hasSize(1);
        assertThat(advertencias.getFirst()).contains("DISPOSITIVO_DESCONOCIDO");
        assertThat(decision.get("decision").asString()).isEqualTo(Decision.APROBAR.name());
    }

    @Test
    @DisplayName("la ráfaga de operaciones del mismo cliente acaba disparando la regla de velocidad")
    void rafagaDeOperacionesDisparaLaRegla() {
        // Prueba de que los contadores de Redis se incrementan de verdad entre evaluaciones: el
        // umbral por defecto es 8 operaciones por cliente en 60 s.
        String cliente = "cli-rafaga-" + UUID.randomUUID();
        String dispositivo = "disp-" + UUID.randomUUID();
        sembrarPerfilConocido(cliente, dispositivo);

        String ultima = null;
        for (int i = 1; i <= 10; i++) {
            ultima = "tx-rafaga-%d-%s".formatted(i, UUID.randomUUID());
            publicar(
                    propiedadesDeKafka.topics().transacciones(),
                    cliente,
                    TransaccionesJson.compraWeb(
                            ultima, cliente, "50.00", "com-100", "5942", "ES",
                            40.4168, -3.7038, "ES", dispositivo, AHORA.toString()));
        }

        JsonNode decision = esperarDecision(ultima);

        var codigos = decision.get("senales").valueStream().map(s -> s.get("codigo").asString()).toList();
        assertThat(codigos).contains("RAFAGA_POR_CLIENTE");
    }

    // ------------------------------------------------------------------ utilidades

    /**
     * Perfil de un cliente asentado en Madrid, con dispositivo y comercio conocidos.
     *
     * <p><strong>El identificador de dispositivo tiene que ser único por test.</strong> El
     * contador de ráfaga por dispositivo vive en Redis con una ventana de 60 segundos y los
     * contenedores se comparten entre clases de test: con una huella fija, el test de ráfaga
     * dejaba el contador por encima del umbral y contaminaba a los que corrían después, que
     * empezaban a ver una señal que su escenario no había provocado.
     */
    private void sembrarPerfilConocido(String cliente, String dispositivo) {
        perfiles.guardar(new PerfilDeComportamiento(
                IdCliente.de(cliente),
                Dinero.de("100.00", "EUR"),
                Dinero.de("500.00", "EUR"),
                Set.of("ES"),
                Set.of("com-100"),
                Set.of(IdDispositivo.de(dispositivo)),
                250L,
                Optional.of(AHORA.minusSeconds(3600)),
                Optional.of(Geolocalizacion.de(40.4168, -3.7038, "ES")),
                AHORA.minusSeconds(3600)));

        // Comprobación de ida y vuelta contra DynamoDB real: si el mapeo perdiera un campo, el
        // resto de este test fallaría por un motivo confuso.
        assertThat(perfiles.buscarPor(IdCliente.de(cliente)))
                .get()
                .satisfies(perfil -> {
                    assertThat(perfil.esFiable()).isTrue();
                    assertThat(perfil.importeMedio()).isEqualTo(Dinero.de("100.00", "EUR"));
                    assertThat(perfil.ultimaPosicion()).isPresent();
                });
    }

    private JsonNode esperarDecision(String idTransaccion) {
        ConsumerRecord<String, String> registro =
                esperarRegistro(propiedadesDeKafka.topics().decisiones(), r -> idTransaccion.equals(r.key()));
        return JSON.readTree(registro.value());
    }
}
