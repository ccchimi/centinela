package com.centinela.fraude.infraestructura.adaptador.entrada.rest;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * Los 401 y 403 de la API, en el mismo formato Problem Details que el resto de errores.
 *
 * <p>Los rechazos de seguridad ocurren en la cadena de filtros, antes del
 * {@code DispatcherServlet}, así que el {@code @RestControllerAdvice} no los ve. Sin esta clase
 * serían los únicos errores de toda la API con otra forma: un cuerpo vacío.
 *
 * <p>La cabecera {@code WWW-Authenticate} la siguen escribiendo los manejadores estándar de
 * Spring ({@code Bearer error="invalid_token"} o {@code insufficient_scope}), que es lo que
 * indica RFC 6750 y lo que un cliente OAuth2 sabe interpretar. Esta clase sólo añade el cuerpo.
 *
 * <p>Dos decisiones que se mantienen de la autenticación anterior:
 *
 * <ul>
 *   <li><strong>El cuerpo del 401 es el mismo falte el token, esté vencido o tenga la firma
 *       mal.</strong> Detallar el motivo en el cuerpo ayudaría más a quien sondea la API que a
 *       un integrador legítimo, que tiene el motivo en el log y en la cabecera estándar.
 *   <li><strong>El token nunca se escribe en el log.</strong> Ni completo ni recortado: un JWT
 *       es una credencial al portador hasta que vence.
 * </ul>
 */
public final class RespuestasDeSeguridad implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(RespuestasDeSeguridad.class);
    private static final String BASE_DE_TIPOS = "https://centinela.example/problemas/";

    private final AuthenticationEntryPoint cabeceraDe401 = new BearerTokenAuthenticationEntryPoint();
    private final AccessDeniedHandler cabeceraDe403 = new BearerTokenAccessDeniedHandler();
    private final Clock reloj;
    private final JsonMapper json;

    public RespuestasDeSeguridad(Clock reloj, JsonMapper json) {
        this.reloj = reloj;
        this.json = json;
    }

    @Override
    public void commence(HttpServletRequest peticion, HttpServletResponse respuesta, AuthenticationException error)
            throws IOException {
        log.warn(
                "Petición rechazada sin autenticación válida: {} {} desde {} ({})",
                peticion.getMethod(),
                peticion.getRequestURI(),
                peticion.getRemoteAddr(),
                error.getClass().getSimpleName());
        try {
            cabeceraDe401.commence(peticion, respuesta, error);
        } catch (jakarta.servlet.ServletException e) {
            throw new IOException(e);
        }
        escribir(
                respuesta,
                HttpStatus.UNAUTHORIZED,
                "No autorizado",
                "Falta el token de acceso o no es válido",
                "no-autorizado",
                "NO_AUTORIZADO");
    }

    @Override
    public void handle(HttpServletRequest peticion, HttpServletResponse respuesta, AccessDeniedException error)
            throws IOException {
        log.warn(
                "Acceso denegado a {} {} para {}",
                peticion.getMethod(),
                peticion.getRequestURI(),
                peticion.getUserPrincipal() != null ? peticion.getUserPrincipal().getName() : "anónimo");
        try {
            cabeceraDe403.handle(peticion, respuesta, error);
        } catch (jakarta.servlet.ServletException e) {
            throw new IOException(e);
        }
        escribir(
                respuesta,
                HttpStatus.FORBIDDEN,
                "Acceso denegado",
                "El token no tiene el rol necesario para esta operación",
                "acceso-denegado",
                "ACCESO_DENEGADO");
    }

    private void escribir(
            HttpServletResponse respuesta, HttpStatus estado, String titulo, String detalle, String tipo, String codigo)
            throws IOException {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(estado, detalle);
        problema.setTitle(titulo);
        problema.setType(URI.create(BASE_DE_TIPOS + tipo));
        problema.setProperty("codigo", codigo);
        problema.setProperty("ocurridoEn", reloj.instant());

        respuesta.setStatus(estado.value());
        respuesta.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        respuesta.setCharacterEncoding(StandardCharsets.UTF_8.name());
        respuesta.getWriter().write(json.writeValueAsString(problema));
    }
}
