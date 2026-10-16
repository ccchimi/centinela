package com.centinela.fraude.infraestructura;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Los contenedores reales del sistema: Kafka, LocalStack (DynamoDB), Redis y Keycloak.
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
     * Keycloak con el mismo realm que usa {@code docker compose}: el fichero versionado en
     * {@code infra/keycloak}, no una copia para tests. Si alguien cambia un rol o una audiencia
     * en el realm, estos tests lo notan.
     *
     * <p>Es el contenedor más lento de la suite (Keycloak arranca en decenas de segundos), pero
     * se paga una sola vez: el caché de contextos lo comparte con el resto de pruebas.
     */
    @Bean
    GenericContainer<?> keycloak() {
        return new GenericContainer<>(DockerImageName.parse("quay.io/keycloak/keycloak:26.8.1"))
                .withExposedPorts(8080)
                .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
                .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
                .withCopyFileToContainer(
                        MountableFile.forHostPath("infra/keycloak/centinela-realm.json"),
                        "/opt/keycloak/data/import/centinela-realm.json")
                .withCommand("start-dev", "--import-realm")
                .waitingFor(Wait.forHttp("/realms/centinela/.well-known/openid-configuration")
                        .forPort(8080)
                        .withStartupTimeout(Duration.ofMinutes(3)));
    }

    /**
     * El emisor que espera la aplicación es la URL por la que el test pide los tokens: sin
     * {@code KC_HOSTNAME}, Keycloak firma con el host de la petición, y el test la hace contra el
     * puerto mapeado. Si no coincidieran, la validación de {@code iss} rechazaría todo.
     */
    @Bean
    DynamicPropertyRegistrar propiedadesDeKeycloak(GenericContainer<?> keycloak) {
        return registro -> {
            String emisor = "http://%s:%d/realms/centinela".formatted(keycloak.getHost(), keycloak.getMappedPort(8080));
            registro.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> emisor);
            registro.add(
                    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                    () -> emisor + "/protocol/openid-connect/certs");
        };
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
