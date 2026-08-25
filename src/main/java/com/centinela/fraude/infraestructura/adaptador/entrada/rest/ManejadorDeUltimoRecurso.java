package com.centinela.fraude.infraestructura.adaptador.entrada.rest;

import java.net.URI;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Red de seguridad: lo que no ha sabido tratar nadie más.
 *
 * <p>Está separado de {@link ManejadorGlobalDeErrores} y con <strong>precedencia mínima</strong>
 * por una razón concreta. Un comodín sobre {@code Exception} dentro de un advice de precedencia
 * máxima capturaría también las excepciones del propio Spring MVC —método no permitido, tipo de
 * contenido no soportado, cuerpo ilegible— y las convertiría todas en 500. Poniéndolo el último,
 * el advice de Problem Details de Spring atiende primero lo que sabe y aquí sólo llega lo que
 * de verdad es un fallo nuestro.
 *
 * <p>Al cliente se le devuelve un mensaje genérico a propósito: el detalle de una excepción
 * inesperada puede revelar nombres de tablas, hosts o topología interna. El detalle completo va
 * al log, que es donde alguien con acceso puede leerlo.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class ManejadorDeUltimoRecurso {

    private static final Logger log = LoggerFactory.getLogger(ManejadorDeUltimoRecurso.class);

    private final Clock reloj;

    public ManejadorDeUltimoRecurso(Clock reloj) {
        this.reloj = reloj;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail inesperado(Exception error) {
        log.error("Error no controlado atendiendo una petición", error);

        ProblemDetail problema = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "La petición no se pudo completar. El incidente ha quedado registrado.");
        problema.setTitle("Error interno");
        problema.setType(URI.create("https://centinela.example/problemas/error-interno"));
        problema.setProperty("codigo", "ERROR_INTERNO");
        problema.setProperty("ocurridoEn", reloj.instant());
        return problema;
    }
}
