package com.centinela.fraude.infraestructura.adaptador.entrada.rest;

import com.centinela.fraude.infraestructura.config.PropiedadesDeSeguridad;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Autenticación por clave compartida en la cabecera {@code X-API-KEY}.
 *
 * <p>Es deliberadamente lo más simple que cierra la brecha: los consumidores de esta API son
 * sistemas internos —el autorizador y la herramienta de analistas—, no personas, y montar un
 * servidor de identidades para dos clientes de confianza sería complejidad sin destinatario.
 * Cuando aparezcan clientes externos, el reemplazo natural es OAuth2 con
 * {@code spring-boot-starter-oauth2-resource-server}, y este filtro se retira entero.
 *
 * <h2>Tres detalles que no son opcionales</h2>
 *
 * <ul>
 *   <li><strong>Comparación en tiempo constante.</strong> {@code String.equals} corta en el
 *       primer carácter distinto, y ese tiempo diferencial permite reconstruir la clave a base
 *       de peticiones. {@link MessageDigest#isEqual} recorre siempre todos los bytes.
 *   <li><strong>La sonda de salud queda exenta.</strong> El balanceador consulta
 *       {@code /actuator/health} sin credenciales; si el filtro la protegiera, el health check
 *       daría 401, el grupo de destino marcaría la tarea como no sana y ECS la reciclaría en
 *       bucle. El servicio nunca llegaría a estar disponible y el motivo no se parecería en
 *       nada a "falta una cabecera".
 *   <li><strong>El fallo se responde igual falte la cabecera o sea incorrecta.</strong> Un 401
 *       distinto para "no la mandaste" y "está mal" confirmaría a quien sondea que la ruta
 *       existe y que la clave que probó tiene el formato correcto.
 * </ul>
 */
public class FiltroDeApiKey extends OncePerRequestFilter {

    public static final String CABECERA = "X-API-KEY";

    private static final Logger log = LoggerFactory.getLogger(FiltroDeApiKey.class);
    private static final AntPathMatcher RUTAS = new AntPathMatcher();

    private final byte[] claveEsperada;
    private final List<String> rutasPublicas;
    private final Clock reloj;
    private final JsonMapper json;

    /**
     * Recibe el {@link JsonMapper} y no un {@code HttpMessageConverter}: Spring Boot no expone
     * los convertidores como beans sueltos —los agrupa en {@code HttpMessageConverters}— así que
     * pedir uno por tipo deja el contexto sin arrancar.
     */
    public FiltroDeApiKey(PropiedadesDeSeguridad propiedades, Clock reloj, JsonMapper json) {
        this.claveEsperada = propiedades.apiKey().getBytes(StandardCharsets.UTF_8);
        this.rutasPublicas = propiedades.rutasPublicas();
        this.reloj = reloj;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest peticion) {
        String ruta = peticion.getRequestURI();
        return rutasPublicas.stream().anyMatch(patron -> RUTAS.match(patron, ruta));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest peticion, HttpServletResponse respuesta, FilterChain cadena)
            throws ServletException, IOException {

        if (!esValida(peticion.getHeader(CABECERA))) {
            // Sin la clave en el log, evidentemente: ni la recibida ni la esperada. Lo útil
            // para investigar es de dónde vino y a qué intentaba llegar.
            log.warn(
                    "Petición rechazada por clave de API inválida: {} {} desde {}",
                    peticion.getMethod(),
                    peticion.getRequestURI(),
                    peticion.getRemoteAddr());
            responderNoAutorizado(respuesta);
            return;
        }

        cadena.doFilter(peticion, respuesta);
    }

    private boolean esValida(String recibida) {
        if (recibida == null || recibida.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(recibida.getBytes(StandardCharsets.UTF_8), claveEsperada);
    }

    /**
     * Responde en el mismo formato Problem Details que el resto de la API. El filtro corre
     * antes del {@code DispatcherServlet}, así que el {@code @RestControllerAdvice} no lo ve:
     * si no se serializara aquí, este sería el único error de toda la API con otra forma.
     */
    private void responderNoAutorizado(HttpServletResponse respuesta) throws IOException {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, "Falta la cabecera %s o su valor no es válido".formatted(CABECERA));
        problema.setTitle("No autorizado");
        problema.setType(URI.create("https://centinela.example/problemas/no-autorizado"));
        problema.setProperty("codigo", "NO_AUTORIZADO");
        problema.setProperty("ocurridoEn", reloj.instant());

        respuesta.setStatus(HttpStatus.UNAUTHORIZED.value());
        respuesta.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        respuesta.setCharacterEncoding(StandardCharsets.UTF_8.name());
        respuesta.getWriter().write(json.writeValueAsString(problema));
    }
}
