package com.centinela.fraude.infraestructura.config;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Propiedades {@code centinela.seguridad}.
 *
 * <p>Lo que valida los tokens —emisor, claves públicas y audiencia— no está aquí sino en las
 * propiedades estándar de Spring Boot ({@code spring.security.oauth2.resourceserver.jwt.*}).
 * Aquí sólo queda lo propio de Centinela: qué rutas quedan fuera de la autenticación.
 *
 * @param rutasPublicas rutas exentas. Por defecto sólo la sonda de salud, porque el balanceador
 *     tiene que poder consultarla sin credenciales; todo lo demás exige un token válido
 */
@Validated
@ConfigurationProperties("centinela.seguridad")
public record PropiedadesDeSeguridad(@NotNull List<String> rutasPublicas) {

    public PropiedadesDeSeguridad {
        rutasPublicas = rutasPublicas == null ? List.of() : List.copyOf(rutasPublicas);
    }
}
