package com.centinela.fraude.aplicacion.servicio;

import com.centinela.fraude.dominio.compartido.Invariantes;
import com.centinela.fraude.dominio.excepcion.ConflictoDeVersion;
import com.centinela.fraude.dominio.modelo.EvaluacionDeFraude;
import com.centinela.fraude.dominio.modelo.PerfilDeComportamiento;
import com.centinela.fraude.dominio.modelo.Transaccion;
import com.centinela.fraude.dominio.modelo.vo.ClaveDeVelocidad;
import com.centinela.fraude.dominio.puerto.entrada.EvaluarTransaccionUseCase;
import com.centinela.fraude.dominio.puerto.salida.ActaDeDecisiones;
import com.centinela.fraude.dominio.puerto.salida.ContadorDeVelocidad;
import com.centinela.fraude.dominio.puerto.salida.RepositorioDePerfiles;
import com.centinela.fraude.dominio.regla.ContextoDeEvaluacion;
import com.centinela.fraude.dominio.regla.MotorDeReglas;
import com.centinela.fraude.dominio.regla.ResultadoDelMotor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementación del caso de uso de evaluación de fraude.
 *
 * <h2>Qué hace y qué no</h2>
 *
 * Orquesta, no decide. Reúne el contexto, invoca el motor, asienta el resultado y realimenta el
 * perfil; ninguna regla de fraude vive aquí. Si mañana el catálogo cambia entero, esta clase no
 * se toca.
 *
 * <h2>Sin anotaciones</h2>
 *
 * No hay {@code @Service} ni inyección por framework. El cableado es un {@code @Bean} en
 * {@code infraestructura.config}. El motivo no es purismo: así el caso de uso completo —que es
 * donde vive la lógica de degradación, la más difícil de probar y la que más veces salva una
 * caída— se prueba con JUnit y Mockito en milisegundos, sin levantar un contexto.
 *
 * <h2>Concurrencia y presupuesto</h2>
 *
 * El perfil y los contadores se piden a la vez sobre hilos virtuales, así que la latencia de
 * E/S es el máximo de las dos y no la suma. Las reglas se ejecutan en serie: son funciones puras
 * de coste microscópico. Kafka ya no aparece en este camino: la decisión se asienta junto con su
 * obligación de publicarse y un relevo en segundo plano la saca.
 *
 * <h2>Degradación en lugar de excepción</h2>
 *
 * El contrato del puerto es total: toda transacción válida recibe una evaluación. Si el perfil
 * no llega a tiempo, se evalúa con un perfil vacío; si los contadores fallan, las reglas de
 * ráfaga se declaran degradadas. En un motor en línea, propagar la excepción equivaldría a
 * bloquear una autorización legítima porque una caché tuvo un mal segundo. Cada degradación
 * queda en {@link EvaluacionDeFraude#advertencias()}.
 */
public final class EvaluadorDeFraudeService implements EvaluarTransaccionUseCase, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(EvaluadorDeFraudeService.class);

    /** Un solo reintento ante conflicto: si vuelve a chocar, otra evaluación ya está aprendiendo. */
    private static final int REINTENTOS_DE_PERFIL = 1;

    private final MotorDeReglas motor;
    private final RepositorioDePerfiles perfiles;
    private final ContadorDeVelocidad contadores;
    private final ActaDeDecisiones acta;
    private final ConfiguracionDelEvaluador configuracion;
    private final Clock reloj;
    private final ExecutorService ejecutor;

    public EvaluadorDeFraudeService(
            MotorDeReglas motor,
            RepositorioDePerfiles perfiles,
            ContadorDeVelocidad contadores,
            ActaDeDecisiones acta,
            ConfiguracionDelEvaluador configuracion,
            Clock reloj) {
        this.motor = Invariantes.noNulo(motor, "motor");
        this.perfiles = Invariantes.noNulo(perfiles, "repositorio de perfiles");
        this.contadores = Invariantes.noNulo(contadores, "contador de velocidad");
        this.acta = Invariantes.noNulo(acta, "acta de decisiones");
        this.configuracion = Invariantes.noNulo(configuracion, "configuración");
        this.reloj = Invariantes.noNulo(reloj, "reloj");
        // Un ejecutor de hilo virtual por tarea no tiene pool que dimensionar ni cola que
        // saturar, así que crearlo aquí no es una decisión de despliegue disfrazada.
        this.ejecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public EvaluacionDeFraude evaluar(Transaccion transaccion) {
        Invariantes.noNulo(transaccion, "transaccion");

        // El presupuesto se mide con nanoTime (monótono) y no con el reloj inyectado: en un test
        // con Clock.fixed el tiempo transcurrido según el reloj sería siempre cero y el
        // presupuesto no vencería nunca. El reloj solo fecha el evento.
        long inicioNanos = System.nanoTime();
        Instant evaluadoEn = reloj.instant();
        List<String> advertencias = new ArrayList<>();

        Set<ClaveDeVelocidad> claves = motor.contadoresNecesarios(transaccion);

        Future<PerfilCargado> futuroPerfil = ejecutor.submit(() -> cargarPerfil(transaccion, evaluadoEn));
        Future<Map<ClaveDeVelocidad, Long>> futuroContadores = ejecutor.submit(() -> resolverContadores(claves));

        PerfilCargado perfilCargado = esperar(
                futuroPerfil,
                inicioNanos,
                "perfil de comportamiento",
                advertencias,
                () -> PerfilCargado.noDisponible(perfilVacioPara(transaccion, evaluadoEn)));

        Map<ClaveDeVelocidad, Long> valores =
                esperar(futuroContadores, inicioNanos, "contadores de velocidad", advertencias, Map::of);

        ContextoDeEvaluacion contexto = new ContextoDeEvaluacion(
                transaccion, perfilCargado.perfil(), valores, configuracion.politica(), evaluadoEn);
        ResultadoDelMotor resultado = motor.ejecutar(contexto);
        advertencias.addAll(resultado.advertencias());

        EvaluacionDeFraude evaluacion = EvaluacionDeFraude.apartirDe(
                transaccion.id(),
                resultado.senales(),
                List.copyOf(advertencias),
                evaluadoEn,
                Duration.ofNanos(System.nanoTime() - inicioNanos));

        asentar(evaluacion, transaccion);
        realimentarPerfil(perfilCargado, transaccion, evaluacion, evaluadoEn);

        if (evaluacion.estaDegradada()) {
            log.warn(
                    "Evaluación degradada para {}: decisión {} con {} advertencia(s) {}",
                    transaccion.descripcionSegura(),
                    evaluacion.decision(),
                    evaluacion.advertencias().size(),
                    evaluacion.advertencias());
        } else if (log.isDebugEnabled()) {
            log.debug("{} -> {} ({})", transaccion.descripcionSegura(), evaluacion.decision(), evaluacion.puntaje());
        }

        return evaluacion;
    }

    // -------------------------------------------------------------------------------------
    // Recolección del contexto
    // -------------------------------------------------------------------------------------

    /**
     * Perfil del cliente y si la lectura fue concluyente.
     *
     * <p>La distinción no es cosmética. Un perfil vacío puede significar dos cosas opuestas:
     * "este cliente es nuevo" o "no pude leer su perfil". Sólo en el primer caso se puede
     * escribir de vuelta; hacerlo en el segundo <strong>sobrescribiría el historial real con uno
     * en blanco</strong>, y bastaría un pico de latencia de DynamoDB para borrarle a un cliente
     * de años su comportamiento aprendido.
     */
    private record PerfilCargado(PerfilDeComportamiento perfil, boolean lecturaConcluyente) {

        static PerfilCargado leido(PerfilDeComportamiento perfil) {
            return new PerfilCargado(perfil, true);
        }

        static PerfilCargado noDisponible(PerfilDeComportamiento provisional) {
            return new PerfilCargado(provisional, false);
        }
    }

    private PerfilCargado cargarPerfil(Transaccion transaccion, Instant evaluadoEn) {
        return PerfilCargado.leido(perfiles.buscarPor(transaccion.cliente())
                // Un perfil de otro cliente significa un error de clave en el adaptador. Es
                // preferible evaluar a ciegas que evaluar contra la normalidad de un tercero.
                .filter(perfil -> perfil.cliente().equals(transaccion.cliente()))
                .orElseGet(() -> perfilVacioPara(transaccion, evaluadoEn)));
    }

    private PerfilDeComportamiento perfilVacioPara(Transaccion transaccion, Instant evaluadoEn) {
        return PerfilDeComportamiento.nuevoPara(
                transaccion.cliente(), configuracion.monedaPorDefecto(), evaluadoEn);
    }

    /**
     * Resuelve los contadores declarados por las reglas.
     *
     * <p>Cada clave se aísla: si una falla, las demás siguen disponibles y solo la regla que
     * dependía de ella se declarará degradada. Una clave ausente del mapa significa "no se pudo
     * obtener", nunca "cero".
     */
    private Map<ClaveDeVelocidad, Long> resolverContadores(Set<ClaveDeVelocidad> claves) {
        Map<ClaveDeVelocidad, Long> valores = new HashMap<>();
        for (ClaveDeVelocidad clave : claves) {
            try {
                valores.put(clave, contadores.incrementarYContar(clave, configuracion.politica().ventanaDeVelocidad()));
            } catch (RuntimeException e) {
                log.warn("No se pudo resolver el contador {}: {}", clave, e.toString());
            }
        }
        return valores;
    }

    /**
     * Espera un resultado dentro de lo que quede del presupuesto; si no llega, sigue con la
     * alternativa y deja constancia.
     */
    private <T> T esperar(
            Future<T> futuro, long inicioNanos, String etiqueta, List<String> advertencias, Supplier<T> alternativa) {

        long restanteNanos = configuracion.presupuestoDeLatencia().toNanos() - (System.nanoTime() - inicioNanos);
        try {
            if (restanteNanos <= 0) {
                // El presupuesto es compartido: si la primera fuente se lo comió entero, a la
                // segunda no le queda tiempo. Pero descartar un resultado que YA está listo
                // sería tirar información gratis y degradaría la evaluación sin motivo, así
                // que se comprueba antes de rendirse.
                if (!futuro.isDone()) {
                    throw new TimeoutException("presupuesto agotado antes de esperar");
                }
                return futuro.get();
            }
            return futuro.get(restanteNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            futuro.cancel(true);
            return degradar(
                    advertencias,
                    alternativa,
                    "%s: no llegó dentro del presupuesto de %d ms"
                            .formatted(etiqueta, configuracion.presupuestoDeLatencia().toMillis()));
        } catch (ExecutionException e) {
            return degradar(advertencias, alternativa, "%s: la consulta falló (%s)".formatted(etiqueta, causaDe(e)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            futuro.cancel(true);
            return degradar(advertencias, alternativa, "%s: la espera fue interrumpida".formatted(etiqueta));
        }
    }

    private <T> T degradar(List<String> advertencias, Supplier<T> alternativa, String motivo) {
        advertencias.add(motivo);
        log.warn("Degradación durante la evaluación: {}", motivo);
        return alternativa.get();
    }

    // -------------------------------------------------------------------------------------
    // Asiento y realimentación
    // -------------------------------------------------------------------------------------

    /**
     * Asentar no puede propagar: la decisión ya está tomada y el llamante la necesita. Que no se
     * haya podido escribir el acta no cambia el veredicto; lo que hace falta es que salte una
     * alarma, no que se caiga la autorización.
     *
     * <p>Sigue siendo el punto que más duele si falla: sin acta no hay auditoría ni publicación.
     * Por eso se registra a nivel ERROR y con la transacción identificada.
     */
    private void asentar(EvaluacionDeFraude evaluacion, Transaccion transaccion) {
        try {
            acta.asentar(evaluacion);
        } catch (RuntimeException e) {
            log.error(
                    "No se pudo asentar la decisión {} de {}: la decisión no se auditará ni se publicará. {}",
                    evaluacion.decision(),
                    transaccion.descripcionSegura(),
                    e.toString());
        }
    }

    /**
     * Incorpora la transacción al perfil del cliente para que la próxima evaluación sepa más.
     *
     * <p>Es el bucle que hace que el motor aprenda solo, y tiene tres guardas:
     *
     * <ul>
     *   <li><strong>Sólo si la lectura fue concluyente.</strong> Escribir a partir de un perfil
     *       provisional borraría el historial real.
     *   <li><strong>Sólo si se aprobó sin fricción.</strong> Lo decide el propio dominio en
     *       {@code aprendiendoDe}: aprender de lo que se sospechó dejaría que un atacante
     *       entrenara el perfil a su favor.
     *   <li><strong>Nunca propaga.</strong> Perder una actualización es aceptable, la siguiente
     *       transacción la vuelve a incorporar; abortar una autorización por ello, no.
     * </ul>
     */
    private void realimentarPerfil(
            PerfilCargado cargado, Transaccion transaccion, EvaluacionDeFraude evaluacion, Instant evaluadoEn) {

        if (!cargado.lecturaConcluyente()) {
            log.debug(
                    "No se realimenta el perfil de {}: la lectura no fue concluyente",
                    transaccion.cliente().enmascarado());
            return;
        }

        PerfilDeComportamiento base = cargado.perfil();
        for (int intento = 0; intento <= REINTENTOS_DE_PERFIL; intento++) {
            Optional<PerfilDeComportamiento> aprendido =
                    base.aprendiendoDe(transaccion, evaluacion.decision(), evaluadoEn);
            if (aprendido.isEmpty()) {
                return;
            }
            try {
                perfiles.guardar(aprendido.orElseThrow());
                return;
            } catch (ConflictoDeVersion e) {
                // Otra evaluación del mismo cliente ganó la carrera. Releer y recalcular sobre
                // su resultado es lo correcto; si vuelve a chocar, se abandona: alguien más
                // está aprendiendo de ese cliente y esta transacción no se pierde de verdad,
                // sólo tarda una operación más en reflejarse.
                if (intento == REINTENTOS_DE_PERFIL) {
                    log.debug("Se abandona la realimentación del perfil de {} tras un conflicto", base.cliente());
                    return;
                }
                Optional<PerfilDeComportamiento> releido = leerParaReintento(transaccion);
                if (releido.isEmpty()) {
                    return;
                }
                base = releido.orElseThrow();
            } catch (RuntimeException e) {
                log.warn("No se pudo realimentar el perfil de {}: {}", base.cliente(), e.toString());
                return;
            }
        }
    }

    private Optional<PerfilDeComportamiento> leerParaReintento(Transaccion transaccion) {
        try {
            return perfiles.buscarPor(transaccion.cliente())
                    .filter(perfil -> perfil.cliente().equals(transaccion.cliente()));
        } catch (RuntimeException e) {
            log.warn("No se pudo releer el perfil para reintentar la realimentación: {}", e.toString());
            return Optional.empty();
        }
    }

    private static String causaDe(ExecutionException e) {
        Throwable causa = e.getCause() == null ? e : e.getCause();
        return causa.getMessage() == null || causa.getMessage().isBlank()
                ? causa.getClass().getSimpleName()
                : "%s: %s".formatted(causa.getClass().getSimpleName(), causa.getMessage());
    }

    /** Libera el ejecutor de hilos virtuales. Lo invoca el ciclo de vida del contenedor. */
    @Override
    public void close() {
        ejecutor.shutdownNow();
    }
}
