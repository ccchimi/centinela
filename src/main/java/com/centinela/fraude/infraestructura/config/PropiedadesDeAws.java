package com.centinela.fraude.infraestructura.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Propiedades {@code centinela.aws}.
 *
 * <p>Validadas al arrancar: una tabla mal escrita en el YAML se descubre en el arranque, no la
 * primera vez que un cliente compra algo.
 *
 * @param endpointOverride vacío en AWS real; apunta a LocalStack en desarrollo y en los tests
 */
@Validated
@ConfigurationProperties("centinela.aws")
public record PropiedadesDeAws(
        @NotBlank String region,
        String endpointOverride,
        @NotNull @Valid DynamoDb dynamodb,
        @NotNull @Valid Cliente cliente) {

    /**
     * @param retencionAuditoriaDias alimenta el TTL de los items de auditoría; DynamoDB los borra
     *     solo, que es la única forma sensata de cumplir una retención regulatoria sin un batch
     */
    public record DynamoDb(
            @NotBlank String tablaPerfiles, @NotBlank String tablaAuditoria, @Positive int retencionAuditoriaDias) {}

    /**
     * @param timeoutApiCall techo de la llamada completa, reintentos incluidos. Tiene que caber
     *     dentro del presupuesto de latencia del motor o el presupuesto no sirve de nada
     * @param timeoutIntento techo de cada intento individual
     */
    public record Cliente(
            @NotNull Duration timeoutApiCall,
            @NotNull Duration timeoutIntento,
            @Positive int maxReintentos,
            @Positive int maxConexiones) {}

    /** El endpoint alternativo, si se configuró uno no vacío. */
    public Optional<URI> endpoint() {
        return Optional.ofNullable(endpointOverride)
                .map(String::strip)
                .filter(valor -> !valor.isEmpty())
                .map(URI::create);
    }
}
