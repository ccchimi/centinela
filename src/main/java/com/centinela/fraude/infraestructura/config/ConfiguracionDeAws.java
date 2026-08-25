package com.centinela.fraude.infraestructura.config;

import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryMode;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

/**
 * Cliente asíncrono de DynamoDB.
 *
 * <h2>Los timeouts son el punto importante</h2>
 *
 * Sin {@code apiCallTimeout}, el SDK reintenta con su backoff por defecto y una llamada puede
 * tardar segundos. Este cliente vive dentro de la ventana de autorización: hay que acotar tanto
 * la llamada completa como cada intento, y hacerlo por debajo del presupuesto de latencia del
 * motor. Si no, el presupuesto lo acabaría cortando el caso de uso y cada evaluación se
 * degradaría por diseño.
 *
 * <p>{@code RetryMode.STANDARD} en vez de {@code LEGACY}: menos reintentos y sin el reintento
 * extra sobre el primer intento, que es lo que se quiere cuando el techo son milisegundos.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PropiedadesDeAws.class)
public class ConfiguracionDeAws {

    @Bean(destroyMethod = "close")
    DynamoDbAsyncClient clienteDeDynamoDb(PropiedadesDeAws propiedades) {
        PropiedadesDeAws.Cliente ajustes = propiedades.cliente();

        var http = NettyNioAsyncHttpClient.builder()
                .maxConcurrency(ajustes.maxConexiones())
                .connectionTimeout(Duration.ofSeconds(2))
                .build();

        var sobreescritura = ClientOverrideConfiguration.builder()
                .apiCallTimeout(ajustes.timeoutApiCall())
                .apiCallAttemptTimeout(ajustes.timeoutIntento())
                .retryStrategy(RetryMode.STANDARD)
                .build();

        var constructor = DynamoDbAsyncClient.builder()
                .region(Region.of(propiedades.region()))
                .httpClient(http)
                .overrideConfiguration(sobreescritura);

        // Sólo en desarrollo y en tests: apunta el cliente a LocalStack. En AWS real la
        // propiedad viene vacía y el SDK resuelve el endpoint por región, como debe ser.
        propiedades.endpoint().ifPresent(constructor::endpointOverride);

        return constructor.build();
    }
}
