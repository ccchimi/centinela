package com.centinela.fraude.infraestructura.adaptador.entrada.rest;

import com.centinela.fraude.dominio.excepcion.ConflictoDeVersion;
import com.centinela.fraude.dominio.excepcion.ErrorDeDominio;
import com.centinela.fraude.dominio.excepcion.RecursoDeDominioNoEncontrado;
import com.centinela.fraude.dominio.excepcion.ReglaDeNegocioIncumplida;
import com.centinela.fraude.dominio.excepcion.ViolacionDeInvariante;
import com.centinela.fraude.infraestructura.adaptador.entrada.contrato.EventoInvalidoException;
import java.net.URI;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Traduce las excepciones a respuestas Problem Details (RFC 9457, antes RFC 7807).
 *
 * <h2>El switch exhaustivo es el objetivo, no un adorno</h2>
 *
 * {@link ErrorDeDominio} se declaró sellado en la primera iteración precisamente para esto. El
 * {@code switch} de {@link #deDominio} no tiene {@code default}: si mañana alguien añade un
 * subtipo de error de dominio, <strong>este fichero deja de compilar</strong> y obliga a decidir
 * qué código HTTP le corresponde. La alternativa —un {@code default} que devuelve 500— habría
 * degradado en silencio un error de negocio perfectamente conocido a un fallo del servidor, y
 * nadie se habría enterado hasta ver la gráfica de cincos.
 *
 * <h2>Por qué la precedencia es explícita</h2>
 *
 * Con {@code spring.mvc.problemdetails.enabled=true}, Spring Boot registra su propio
 * {@code @ControllerAdvice} —el que convierte 405, 415 o un cuerpo ilegible en Problem
 * Details— y lo hace con orden 0. Un advice sin {@code @Order} queda en el último lugar, así
 * que el de Spring atendía {@code MethodArgumentNotValidException} antes que este y la
 * respuesta salía sin la lista de campos inválidos. Con precedencia máxima ganan los
 * manejadores de aquí y el de Spring sigue cubriendo todo lo que este no declara.
 *
 * <p>Por eso el comodín {@code Exception} <strong>no</strong> vive en esta clase sino en
 * {@link ManejadorDeUltimoRecurso}, con precedencia mínima: aquí arriba se tragaría también las
 * excepciones del framework y las convertiría todas en 500.
 *
 * <h2>Qué se cuenta y qué no</h2>
 *
 * El {@code detail} lleva el mensaje del dominio, que está escrito para ser leído por quien
 * integra y no filtra datos personales: los identificadores de cliente ya salen enmascarados
 * desde los value objects. Las trazas de pila no salen nunca.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ManejadorGlobalDeErrores {

    /** Espacio de nombres de los tipos de problema; permite documentar cada código en un sitio. */
    private static final String BASE_DE_TIPOS = "https://centinela.example/problemas/";

    private final Clock reloj;

    public ManejadorGlobalDeErrores(Clock reloj) {
        this.reloj = reloj;
    }

    /**
     * Errores del núcleo. Recorre la jerarquía sellada sin {@code default}.
     */
    @ExceptionHandler(ErrorDeDominio.class)
    public ProblemDetail deDominio(ErrorDeDominio error) {
        return switch (error) {
            // El emisor mandó algo que el modelo no admite.
            case ViolacionDeInvariante e ->
                problema(HttpStatus.BAD_REQUEST, "Datos de la transacción no válidos", e);

            // Sintácticamente correcto, pero el dominio lo rechaza.
            case ReglaDeNegocioIncumplida e ->
                problema(HttpStatus.UNPROCESSABLE_CONTENT, "La operación incumple una regla de negocio", e);

            case RecursoDeDominioNoEncontrado e -> problema(HttpStatus.NOT_FOUND, "Recurso no encontrado", e);

            // Carrera de escritura: reintentar es razonable, y por eso 409 y no 500.
            case ConflictoDeVersion e ->
                problema(HttpStatus.CONFLICT, "El recurso cambió mientras se procesaba la petición", e);
        };
    }

    /** Traducción del cable al dominio fallida: el mensaje era válido pero no evaluable. */
    @ExceptionHandler(EventoInvalidoException.class)
    public ProblemDetail deEventoInvalido(EventoInvalidoException error) {
        ProblemDetail problema = base(HttpStatus.BAD_REQUEST, "Transacción no evaluable", error.getMessage());
        problema.setType(URI.create(BASE_DE_TIPOS + "transaccion-no-evaluable"));
        problema.setProperty("codigo", "TRANSACCION_NO_EVALUABLE");
        return problema;
    }

    /**
     * Bean Validation sobre el cuerpo de la petición.
     *
     * <p>Devuelve la lista de campos y qué les pasa, ordenada. Un 400 con "petición inválida" y
     * nada más obliga a quien integra a adivinar, y adivinar contra un motor de fraude significa
     * abrir un ticket.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail deValidacion(MethodArgumentNotValidException error) {
        List<String> errores = error.getBindingResult().getFieldErrors().stream()
                .map(campo -> "%s: %s".formatted(campo.getField(), campo.getDefaultMessage()))
                .sorted(Comparator.naturalOrder())
                .toList();

        ProblemDetail problema =
                base(HttpStatus.BAD_REQUEST, "La petición no supera la validación", "Hay %d campo(s) inválido(s)".formatted(errores.size()));
        problema.setType(URI.create(BASE_DE_TIPOS + "peticion-invalida"));
        problema.setProperty("codigo", "PETICION_INVALIDA");
        problema.setProperty("errores", errores);
        return problema;
    }

    private ProblemDetail problema(HttpStatusCode estado, String titulo, ErrorDeDominio error) {
        ProblemDetail problema = base(estado, titulo, error.getMessage());
        problema.setType(URI.create(BASE_DE_TIPOS + error.codigo().toLowerCase(java.util.Locale.ROOT).replace('_', '-')));
        // El código estable va también como propiedad: es lo que un cliente debe programar,
        // en lugar de parsear el texto del título o del detalle.
        problema.setProperty("codigo", error.codigo());
        return problema;
    }

    private ProblemDetail base(HttpStatusCode estado, String titulo, String detalle) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(estado, detalle);
        problema.setTitle(titulo);
        problema.setProperty("ocurridoEn", reloj.instant());
        return problema;
    }
}
