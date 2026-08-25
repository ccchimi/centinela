package com.centinela.fraude.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.KafkaHeaders;

/**
 * La cola de descartes: qué acaba en ella, con qué contenido y con qué diagnóstico.
 *
 * <p>Es la parte del sistema que más se da por supuesta y menos se prueba. Un mensaje que no se
 * puede evaluar no debe perderse ni bloquear la partición, y quien investigue el incidente
 * necesita encontrar en la DLQ <em>exactamente lo que mandó el emisor</em>, no una versión
 * reinterpretada por nuestro deserializador.
 */
@DisplayName("Cola de descartes")
class ColaDeDescartesIT extends PruebaDeIntegracion {

    private static final Instant AHORA = Instant.parse("2026-08-24T12:00:00Z");

    @Test
    @DisplayName("un JSON roto va a la DLQ con los bytes originales intactos")
    void jsonRotoConservaLosBytesOriginales() {
        String clave = "roto-" + UUID.randomUUID();
        String basura = TransaccionesJson.malformado();

        publicar(propiedadesDeKafka.topics().transacciones(), clave, basura);

        ConsumerRecord<String, byte[]> descarte =
                esperarRegistroCrudo(propiedadesDeKafka.topics().descartes(), r -> clave.equals(r.key()));

        // Los bytes tienen que llegar tal cual: son la única evidencia de qué se envió. Si el
        // serializador de la DLQ los hubiera pasado por JSON, aquí habría una cadena Base64.
        assertThat(new String(descarte.value(), StandardCharsets.UTF_8)).isEqualTo(basura);
        assertThat(cabecera(descarte, KafkaHeaders.DLT_ORIGINAL_TOPIC))
                .isEqualTo(propiedadesDeKafka.topics().transacciones());
        assertThat(cabecera(descarte, KafkaHeaders.DLT_EXCEPTION_FQCN)).contains("DeserializationException");
    }

    @Test
    @DisplayName("un evento sin cliente va a la DLQ, con el motivo de validación en la cabecera")
    void eventoInvalidoLlevaElMotivo() {
        String clave = "sin-cliente-" + UUID.randomUUID();
        String transaccion = "tx-" + UUID.randomUUID();

        publicar(
                propiedadesDeKafka.topics().transacciones(),
                clave,
                TransaccionesJson.sinCliente(transaccion, AHORA.toString()));

        ConsumerRecord<String, byte[]> descarte =
                esperarRegistroCrudo(propiedadesDeKafka.topics().descartes(), r -> clave.equals(r.key()));

        // El FQCN de primer nivel es siempre el envoltorio de Spring Kafka; el error real de la
        // aplicación va en la cabecera de causa. Merece la pena dejarlo fijado en un test:
        // quien triaje la DLQ mirará la cabecera equivocada la primera vez.
        assertThat(cabecera(descarte, KafkaHeaders.DLT_EXCEPTION_FQCN))
                .isEqualTo("org.springframework.kafka.listener.ListenerExecutionFailedException");
        assertThat(cabecera(descarte, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN)).contains("EventoInvalidoException");
        assertThat(cabecera(descarte, KafkaHeaders.DLT_EXCEPTION_STACKTRACE)).contains("idCliente");
        // Aquí sí se reserializa: el valor era un DTO ya deserializado, y publicarlo como JSON
        // legible es más útil que los bytes.
        assertThat(new String(descarte.value(), StandardCharsets.UTF_8)).contains(transaccion);
    }

    @Test
    @DisplayName("un instrumento de pago desconocido se rechaza en vez de adivinarse")
    void instrumentoDesconocidoSeRechaza() {
        String clave = "instrumento-" + UUID.randomUUID();
        String transaccion = "tx-" + UUID.randomUUID();

        publicar(
                propiedadesDeKafka.topics().transacciones(),
                clave,
                TransaccionesJson.instrumentoDesconocido(transaccion, "cli-" + UUID.randomUUID(), AHORA.toString()));

        ConsumerRecord<String, byte[]> descarte =
                esperarRegistroCrudo(propiedadesDeKafka.topics().descartes(), r -> clave.equals(r.key()));

        assertThat(cabecera(descarte, KafkaHeaders.DLT_EXCEPTION_STACKTRACE))
                .contains("CHEQUE_AL_PORTADOR")
                .contains("TARJETA");
    }

    @Test
    @DisplayName("un mensaje inválido no bloquea a los que vienen detrás")
    void unMensajeInvalidoNoBloqueaLaParticion() {
        // El motivo por el que EventoInvalidoException se declara no reintentable: si se
        // reintentara con backoff, la partición quedaría parada durante todo el ciclo y las
        // transacciones legítimas de detrás llegarían tarde a su ventana de autorización.
        String claveMala = "mala-" + UUID.randomUUID();
        String clienteBueno = "cli-" + UUID.randomUUID();
        String transaccionBuena = "tx-" + UUID.randomUUID();

        publicar(propiedadesDeKafka.topics().transacciones(), claveMala, TransaccionesJson.malformado());
        publicar(
                propiedadesDeKafka.topics().transacciones(),
                clienteBueno,
                TransaccionesJson.compraWeb(
                        transaccionBuena, clienteBueno, "75.00", "com-100", "5942", "ES",
                        40.4168, -3.7038, "ES", "disp-x", AHORA.toString()));

        // La buena se evalúa igualmente.
        ConsumerRecord<String, String> decision =
                esperarRegistro(propiedadesDeKafka.topics().decisiones(), r -> transaccionBuena.equals(r.key()));
        assertThat(decision.value()).contains(transaccionBuena);

        // Y la mala está en la DLQ.
        esperarRegistroCrudo(propiedadesDeKafka.topics().descartes(), r -> claveMala.equals(r.key()));
    }

    private static String cabecera(ConsumerRecord<String, byte[]> registro, String nombre) {
        Header cabecera = registro.headers().lastHeader(nombre);
        assertThat(cabecera).as("cabecera '%s' ausente en el descarte", nombre).isNotNull();
        return new String(cabecera.value(), StandardCharsets.UTF_8);
    }
}
