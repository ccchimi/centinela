package com.centinela.fraude.infraestructura;

import com.centinela.fraude.infraestructura.config.PropiedadesDeAws;
import com.centinela.fraude.infraestructura.config.PropiedadesDeKafka;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Predicate;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.kafka.KafkaContainer;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

/**
 * Base de las pruebas de integración: contexto completo contra contenedores reales.
 *
 * <p>Todas las subclases comparten exactamente la misma configuración de contexto. No es
 * casualidad: el caché de contextos de Spring Test lo reutiliza, y con él los contenedores, así
 * que Kafka, LocalStack y Redis arrancan una sola vez para toda la suite. Cualquier
 * {@code @SpringBootTest} con propiedades distintas partiría el caché y multiplicaría el tiempo.
 *
 * <p>Los mensajes se producen y se consumen con clientes crudos de Kafka, no con
 * {@code KafkaTemplate}. Es deliberado: el emisor real es un sistema ajeno que escribe JSON
 * plano, y usar la plantilla de la aplicación probaría que sabemos deserializar lo que nosotros
 * mismos serializamos, que es justo lo que no interesa comprobar.
 */
@SpringBootTest(
        properties = {
            // El productor del test publica antes de que el grupo termine de asignarse; sin
            // 'earliest' el listener se perdería el primer mensaje y el test sería inestable.
            "spring.kafka.consumer.auto-offset-reset=earliest",
            "spring.kafka.listener.concurrency=1",
            "centinela.kafka.topics.particiones=1",
            "centinela.kafka.reintentos.espera-inicial=50ms",
            "centinela.kafka.reintentos.espera-maxima=200ms",
            // ---------------------------------------------------------------------------------
            // Los tiempos de producción (120 ms de API call, 150 ms de presupuesto) están
            // calculados para DynamoDB en la misma zona de disponibilidad. LocalStack a través
            // de la red de Docker es uno o dos órdenes de magnitud más lento, así que con los
            // valores reales toda evaluación saldría degradada por timeout y estos tests
            // medirían el rendimiento del portátil en vez de la corrección del sistema.
            //
            // Se relajan SOLO aquí. Los valores de application.yml no se tocan: el presupuesto
            // de latencia es un requisito del producto, no una constante de conveniencia.
            // ---------------------------------------------------------------------------------
            "centinela.aws.cliente.timeout-api-call=10s",
            "centinela.aws.cliente.timeout-intento=5s",
            "centinela.motor.presupuesto-latencia=10s",
            "logging.level.com.centinela.fraude=DEBUG"
        })
@Import(ContenedoresDeIntegracion.class)
public abstract class PruebaDeIntegracion {

    protected static final Duration ESPERA = Duration.ofSeconds(30);

    @Autowired
    protected KafkaContainer kafka;

    @Autowired
    protected DynamoDbAsyncClient dynamo;

    @Autowired
    protected PropiedadesDeAws propiedadesDeAws;

    @Autowired
    protected PropiedadesDeKafka propiedadesDeKafka;

    @Autowired
    protected StringRedisTemplate redis;

    @BeforeEach
    void prepararTablas() {
        TablasDePrueba.crear(dynamo, propiedadesDeAws);
    }

    // ------------------------------------------------------------------ producción

    /** Publica JSON crudo, como lo haría el sistema de autorización de origen. */
    protected void publicar(String topico, String clave, String json) {
        Properties config = new Properties();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        config.put(ProducerConfig.ACKS_CONFIG, "all");

        try (var productor = new KafkaProducer<String, String>(config)) {
            productor.send(new ProducerRecord<>(topico, clave, json)).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo publicar en " + topico, e);
        }
    }

    // ------------------------------------------------------------------ consumo

    /**
     * Espera al primer registro del topic que cumpla el predicado.
     *
     * <p>Filtra en vez de leer sólo el último porque los contenedores se comparten entre tests y
     * el topic arrastra lo que publicaron los anteriores. Cada test usa identificadores propios,
     * así que el predicado sobre la clave lo hace determinista.
     */
    protected ConsumerRecord<String, String> esperarRegistro(
            String topico, Predicate<ConsumerRecord<String, String>> criterio) {
        try (var consumidor = consumidorDeTexto()) {
            consumidor.subscribe(List.of(topico));
            long limite = System.nanoTime() + ESPERA.toNanos();

            while (System.nanoTime() < limite) {
                ConsumerRecords<String, String> lote = consumidor.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> registro : lote) {
                    if (criterio.test(registro)) {
                        return registro;
                    }
                }
            }
        }
        throw new AssertionError(
                "No llegó ningún registro que cumpliera el criterio en %s sobre el topic '%s'"
                        .formatted(ESPERA, topico));
    }

    /** Igual que {@link #esperarRegistro} pero sin deserializar: la DLQ puede traer basura. */
    protected ConsumerRecord<String, byte[]> esperarRegistroCrudo(
            String topico, Predicate<ConsumerRecord<String, byte[]>> criterio) {
        try (var consumidor = new KafkaConsumer<String, byte[]>(
                configuracionDeConsumo(), new StringDeserializer(), new ByteArrayDeserializer())) {
            consumidor.subscribe(List.of(topico));
            long limite = System.nanoTime() + ESPERA.toNanos();

            while (System.nanoTime() < limite) {
                ConsumerRecords<String, byte[]> lote = consumidor.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, byte[]> registro : lote) {
                    if (criterio.test(registro)) {
                        return registro;
                    }
                }
            }
        }
        throw new AssertionError(
                "No llegó ningún registro a la cola de descartes '%s' en %s".formatted(topico, ESPERA));
    }

    private KafkaConsumer<String, String> consumidorDeTexto() {
        return new KafkaConsumer<>(configuracionDeConsumo(), new StringDeserializer(), new StringDeserializer());
    }

    private Map<String, Object> configuracionDeConsumo() {
        // Grupo nuevo en cada llamada: el test quiere leer el topic entero desde el principio,
        // sin heredar los offsets confirmados por otra comprobación.
        return Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "verificador-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false",
                ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
    }
}
