package com.centinela.fraude.infraestructura.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Propiedades {@code centinela.kafka}: topics propios y política de reintentos.
 *
 * <p>Separadas de {@code spring.kafka.*}, que configura el transporte. Aquí va lo que es
 * decisión de este sistema: a qué topics habla y cuánto insiste antes de rendirse.
 */
@Validated
@ConfigurationProperties("centinela.kafka")
public record PropiedadesDeKafka(@NotNull @Valid Topics topics, @NotNull @Valid Reintentos reintentos) {

    /**
     * @param descartes cola de descartes (DLQ). Nada se tira: en fraude, un mensaje perdido es
     *     una autorización sin control
     */
    public record Topics(
            @NotBlank String transacciones,
            @NotBlank String decisiones,
            @NotBlank String descartes,
            @Positive int particiones,
            @Positive short factorDeReplicacion) {}

    /**
     * @param intentosMaximos reintentos <em>además</em> de la entrega original
     * @param esperaInicial primera espera; crece por {@code multiplicador} hasta {@code esperaMaxima}
     */
    public record Reintentos(
            @Positive int intentosMaximos,
            @NotNull Duration esperaInicial,
            @Positive double multiplicador,
            @NotNull Duration esperaMaxima,
            @NotNull Duration timeoutDePublicacion) {}
}
