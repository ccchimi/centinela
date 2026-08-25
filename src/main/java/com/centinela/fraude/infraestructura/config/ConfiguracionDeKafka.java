package com.centinela.fraude.infraestructura.config;

import com.centinela.fraude.dominio.excepcion.ViolacionDeInvariante;
import com.centinela.fraude.infraestructura.adaptador.entrada.kafka.EventoInvalidoException;
import com.centinela.fraude.infraestructura.adaptador.salida.kafka.SerializadorDeDescartes;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Reintentos, cola de descartes y declaración de topics.
 *
 * <h2>Qué se reintenta y qué no</h2>
 *
 * Un fallo transitorio —DynamoDB con un pico de latencia, el broker recolocando particiones—
 * merece reintentos con espera creciente. Un mensaje malformado, no: reintentarlo mantiene la
 * partición bloqueada durante todo el backoff y retrasa las transacciones que vienen detrás,
 * que sí son válidas. Por eso {@link EventoInvalidoException} y {@link ViolacionDeInvariante}
 * se declaran no reintentables y van directas a la cola de descartes.
 *
 * <p>Los errores de deserialización ya están en la lista de no reintentables por defecto de
 * Spring Kafka; se declaran igualmente los nuestros porque el criterio no es obvio y conviene
 * que esté escrito.
 */
@Configuration(proxyBeanMethods = false)
public class ConfiguracionDeKafka {

    private static final Logger log = LoggerFactory.getLogger(ConfiguracionDeKafka.class);

    // ------------------------------------------------------------------ topics

    @Bean
    NewTopic topicoDeTransacciones(PropiedadesDeKafka propiedades) {
        return topico(propiedades.topics().transacciones(), propiedades);
    }

    @Bean
    NewTopic topicoDeDecisiones(PropiedadesDeKafka propiedades) {
        return topico(propiedades.topics().decisiones(), propiedades);
    }

    /**
     * La cola de descartes va con una sola partición a propósito: su volumen es residual y lo
     * que importa es poder leerla entera en orden cuando alguien investiga un incidente.
     */
    @Bean
    NewTopic topicoDeDescartes(PropiedadesDeKafka propiedades) {
        return TopicBuilder.name(propiedades.topics().descartes())
                .partitions(1)
                .replicas(propiedades.topics().factorDeReplicacion())
                .build();
    }

    private static NewTopic topico(String nombre, PropiedadesDeKafka propiedades) {
        return TopicBuilder.name(nombre)
                .partitions(propiedades.topics().particiones())
                .replicas(propiedades.topics().factorDeReplicacion())
                .build();
    }

    // ------------------------------------------------------------------ descartes

    /**
     * Plantilla dedicada a la cola de descartes.
     *
     * <p>Hereda la configuración de conexión de la fábrica que autoconfigura Spring Boot —para no
     * duplicar bootstrap servers, seguridad ni acuses— pero sustituye los serializadores: el
     * valor que llega a la DLQ puede ser el DTO o los bytes crudos del mensaje original, y eso
     * lo resuelve {@link SerializadorDeDescartes}.
     */
    @Bean
    KafkaTemplate<String, Object> plantillaDeDescartes(ProducerFactory<?, ?> fabricaAutoconfigurada) {
        Map<String, Object> configuracion = new HashMap<>(fabricaAutoconfigurada.getConfigurationProperties());
        configuracion.remove(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG);
        configuracion.remove(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG);

        var fabrica = new DefaultKafkaProducerFactory<String, Object>(
                configuracion, new StringSerializer(), new SerializadorDeDescartes());
        return new KafkaTemplate<>(fabrica);
    }

    @Bean
    DefaultErrorHandler manejadorDeErrores(
            KafkaTemplate<String, Object> plantillaDeDescartes, PropiedadesDeKafka propiedades) {

        var recuperador = new DeadLetterPublishingRecoverer(
                plantillaDeDescartes,
                // Partición -1: que Kafka elija. Fijar la partición de origen fallaría si la
                // DLQ tiene menos particiones que el topic de entrada, que es justo el caso.
                (registro, excepcion) -> new TopicPartition(propiedades.topics().descartes(), -1));

        var reintentos = propiedades.reintentos();
        // Spring Framework 7 retiró ExponentialBackOffWithMaxRetries; el tope de reintentos
        // ahora es setMaxAttempts sobre el propio ExponentialBackOff.
        var espera = new ExponentialBackOff();
        espera.setMaxAttempts(reintentos.intentosMaximos());
        espera.setInitialInterval(reintentos.esperaInicial().toMillis());
        espera.setMultiplier(reintentos.multiplicador());
        espera.setMaxInterval(reintentos.esperaMaxima().toMillis());
        // Jitter: si una dependencia se cae, todos los consumidores fallan a la vez y
        // reintentarían sincronizados, machacándola justo cuando intenta recuperarse.
        espera.setJitter(reintentos.esperaInicial().toMillis() / 2);

        var manejador = new DefaultErrorHandler(recuperador, espera);
        manejador.addNotRetryableExceptions(EventoInvalidoException.class, ViolacionDeInvariante.class);
        manejador.setRetryListeners((registro, excepcion, intento) -> log.warn(
                "Reintento {} de {}-{}@{}: {}",
                intento,
                registro.topic(),
                registro.partition(),
                registro.offset(),
                excepcion.getMessage()));

        return manejador;
    }
}
