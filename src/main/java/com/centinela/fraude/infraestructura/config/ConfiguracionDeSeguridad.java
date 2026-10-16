package com.centinela.fraude.infraestructura.config;

import com.centinela.fraude.infraestructura.adaptador.entrada.rest.RespuestasDeSeguridad;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.json.JsonMapper;

/**
 * Autenticación y autorización de la API REST: OAuth2 Resource Server con JWT.
 *
 * <p>Reemplaza a la clave compartida en {@code X-API-KEY}. Una clave compartida no distingue
 * entre consumidores, no caduca sola y su rotación exige coordinar a todos a la vez. Con tokens
 * de un proveedor OIDC (Keycloak en local) cada consumidor tiene su propia identidad y sus
 * propios roles, los tokens vencen en minutos y revocar a uno no afecta al resto.
 *
 * <h2>Qué se valida de cada token</h2>
 *
 * <p>Firma contra las claves públicas del emisor (JWKS), vencimiento, emisor ({@code iss}) y
 * audiencia ({@code aud} debe incluir {@code centinela-api}). Las cuatro las configura Spring
 * Boot a partir de {@code spring.security.oauth2.resourceserver.jwt.*}; la de audiencia es la que
 * más se olvida y la que evita que un token emitido para <em>otro</em> servicio del mismo realm
 * sirva también aquí.
 *
 * <h2>Quién puede qué</h2>
 *
 * <table>
 *   <tr><th>Operación</th><th>Rol</th><th>Consumidor</th></tr>
 *   <tr><td>{@code POST /api/v1/evaluaciones}</td><td>{@code evaluador}</td><td>el autorizador de tarjetas</td></tr>
 *   <tr><td>{@code GET /api/v1/evaluaciones/**}</td><td>{@code evaluador} o {@code analista}</td><td>autorizador y analistas</td></tr>
 *   <tr><td>{@code GET /api/v1/perfiles/**}</td><td>{@code analista}</td><td>herramienta de analistas</td></tr>
 * </table>
 *
 * <p>El perfil de comportamiento de un cliente es el dato más sensible que expone la API —dónde
 * compra, con qué dispositivos, cuánto gasta— y el autorizador no lo necesita para nada: por eso
 * no tiene acceso. Cualquier ruta que no esté en la tabla se deniega aunque el token sea válido,
 * de modo que un endpoint nuevo nace cerrado hasta que alguien decide quién puede usarlo.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PropiedadesDeSeguridad.class)
public class ConfiguracionDeSeguridad {

    static final String EVALUADOR = "EVALUADOR";
    static final String ANALISTA = "ANALISTA";

    @Bean
    SecurityFilterChain cadenaDeSeguridad(
            HttpSecurity http, PropiedadesDeSeguridad propiedades, RespuestasDeSeguridad respuestas) throws Exception {

        String[] rutasPublicas = propiedades.rutasPublicas().toArray(String[]::new);

        http
                // API sin estado y autenticada por cabecera: no hay cookie de sesión que un
                // tercer sitio pueda hacer viajar, que es lo único contra lo que protege CSRF.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sesion -> sesion.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(reglas -> reglas
                        .requestMatchers(rutasPublicas).permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/evaluaciones").hasRole(EVALUADOR)
                        .requestMatchers(HttpMethod.GET, "/api/v1/evaluaciones/**").hasAnyRole(EVALUADOR, ANALISTA)
                        .requestMatchers(HttpMethod.GET, "/api/v1/perfiles/**").hasRole(ANALISTA)
                        // Las páginas de error de Spring también pasan por la cadena: sin esto,
                        // un 404 de un usuario autenticado se convertiría en 403.
                        .requestMatchers("/error").permitAll()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(servidor -> servidor
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(new ConvertidorDeRolesDeKeycloak()))
                        .authenticationEntryPoint(respuestas)
                        .accessDeniedHandler(respuestas))
                .exceptionHandling(errores -> errores
                        .authenticationEntryPoint(respuestas)
                        .accessDeniedHandler(respuestas));

        return http.build();
    }

    @Bean
    RespuestasDeSeguridad respuestasDeSeguridad(Clock reloj, JsonMapper json) {
        return new RespuestasDeSeguridad(reloj, json);
    }
}
