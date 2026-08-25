package com.centinela.fraude.infraestructura;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Los contenedores reales del sistema: Kafka, LocalStack (DynamoDB) y Redis.
 *
 * <p>Se declaran como {@code @Bean} y no como campos {@code static}, que es la forma moderna:
 * así Spring gestiona su ciclo de vida y, sobre todo, <strong>el caché de contextos de Spring
 * Test los reutiliza entre clases de test</strong>. Con campos estáticos y
 * {@code @DynamicPropertySource} cada clase tendería a levantar su propio juego, y arrancar
 * Kafka y LocalStack tres veces multiplica por tres el tiempo de la suite.
 *
 * <p>{@code @ServiceConnection} cubre Kafka y Redis: Spring Boot deduce el host y el puerto del
 * contenedor y sobrescribe {@code spring.kafka.bootstrap-servers} y {@code spring.data.redis.*}
 * sin que haya que escribirlo. Para DynamoDB no existe una fábrica equivalente —el endpoint es
 * una propiedad nuestra, no de Boot— así que se registra a mano con un
 * {@link DynamicPropertyRegistrar}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ContenedoresDeIntegracion {

    static {
        // El SDK de AWS exige credenciales aunque hable con LocalStack, que acepta cualquiera.
        // Se ponen como propiedades de sistema porque SystemPropertyCredentialsProvider forma
        // parte de la cadena por defecto: así el código de producción no necesita una rama
        // "si estoy en un test". La región la fija la propiedad centinela.aws.region.
        System.setProperty("aws.accessKeyId", "test");
        System.setProperty("aws.secretAccessKey", "test");
    }

    /** Imagen nativa de Apache Kafka en modo KRaft: arranca en un par de segundos, sin ZooKeeper. */
    @Bean
    @ServiceConnection
    KafkaContainer kafka() {
        return new KafkaContainer(DockerImageName.parse("apache/kafka-native:3.9.1")).withReuse(false);
    }

    @Bean
    @ServiceConnection("redis")
    GenericContainer<?> redis() {
        return new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
    }

    @Bean
    LocalStackContainer localstack() {
        return new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8"));
    }

    /**
     * Apunta el cliente de DynamoDB a LocalStack.
     *
     * <p>{@code DynamicPropertyRegistrar} es un bean, así que puede recibir el contenedor ya
     * arrancado por inyección. La alternativa clásica, {@code @DynamicPropertySource}, es
     * estática y obligaría a que el contenedor también lo fuera.
     */
    @Bean
    DynamicPropertyRegistrar propiedadesDeLocalStack(LocalStackContainer localstack) {
        return registro -> {
            registro.add("centinela.aws.endpoint-override", () -> localstack.getEndpoint().toString());
            registro.add("centinela.aws.region", localstack::getRegion);
        };
    }
}
