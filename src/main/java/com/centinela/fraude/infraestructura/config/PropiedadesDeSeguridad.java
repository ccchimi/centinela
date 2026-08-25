package com.centinela.fraude.infraestructura.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Propiedades {@code centinela.seguridad}.
 *
 * <p>{@code @NotBlank} sobre la clave no es decorativo: en {@code application-prod.yml} la
 * propiedad se resuelve sin valor por defecto, así que <strong>si la variable de entorno falta,
 * la aplicación no arranca</strong>. Es deliberado. La alternativa —una clave vacía que el
 * filtro interpretaría como "sin autenticación"— convertiría un despliegue mal configurado en
 * una API de fraude abierta a Internet, y nadie lo notaría hasta que alguien la encontrara.
 *
 * @param apiKey clave compartida que debe llegar en la cabecera {@code X-API-KEY}
 * @param rutasPublicas rutas exentas. Por defecto sólo la sonda de salud, porque el balanceador
 *     tiene que poder consultarla sin credenciales; todo lo demás exige clave
 */
@Validated
@ConfigurationProperties("centinela.seguridad")
public record PropiedadesDeSeguridad(@NotBlank String apiKey, @NotNull List<String> rutasPublicas) {

    /** Valor por defecto en desarrollo; en producción no existe y el arranque falla sin clave. */
    public static final String CLAVE_DE_DESARROLLO = "clave-de-desarrollo-no-usar-en-produccion";

    public PropiedadesDeSeguridad {
        rutasPublicas = rutasPublicas == null ? List.of() : List.copyOf(rutasPublicas);
    }

    /** Se está corriendo con la clave de ejemplo. El arranque lo avisa a gritos. */
    public boolean usaClaveDeDesarrollo() {
        return CLAVE_DE_DESARROLLO.equals(apiKey);
    }
}
