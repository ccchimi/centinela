package com.centinela.fraude.aplicacion.servicio;

import com.centinela.fraude.dominio.compartido.Invariantes;
import com.centinela.fraude.dominio.modelo.EvaluacionDeFraude;
import com.centinela.fraude.dominio.modelo.PerfilDeComportamiento;
import com.centinela.fraude.dominio.modelo.Transaccion;
import com.centinela.fraude.dominio.modelo.vo.ClaveDeVelocidad;
import com.centinela.fraude.dominio.puerto.entrada.EvaluarTransaccionUseCase;
import com.centinela.fraude.dominio.puerto.salida.ContadorDeVelocidad;
import com.centinela.fraude.dominio.puerto.salida.PublicadorDeDecisiones;
import com.centinela.fraude.dominio.puerto.salida.RegistroDeAuditoria;
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
 * Orquesta, no decide. Reúne el contexto, invoca el motor y difunde el resultado; ninguna regla
 * de fraude vive aquí. Si mañana el catálogo cambia entero, esta clase no se toca.
 *
 * <h2>Sin anotaciones</h2>
 *
 * No hay {@code @Service} ni inyección por framework. El cableado será un {@code @Bean} en
 * {@code infraestructura.config}. El motivo no es purismo: así el caso de uso completo —que es
 * donde vive la lógica de degradación, la más difícil de probar y la que más veces salva una
 * caída— se prueba con JUnit y Mockito en milisegundos, sin levantar un contexto.
 *
 * <h2>Concurrencia</h2>
 *
 * El perfil y los contadores se piden a la vez sobre hilos virtuales, así que la latencia de
 * E/S es el máximo de las dos y no la suma. Las reglas, en cambio, se ejecutan en serie: son
 * funciones puras de coste microscópico y repartirlas costaría más en coordinación de lo que
 * ahorraría.
 *
 * <h2>Degradación en lugar de excepción</h2>
 *
 * El contrato del puerto es total: toda transacción válida recibe una evaluación. Si el perfil
 * no llega a tiempo, se evalúa con un perfil vacío; si los contadores fallan, las reglas de
 * ráfaga se declaran degradadas. En un motor en línea, propagar la excepción equivaldría a
 * bloquear una autorización legítima porque una caché tuvo un mal segundo.
 *
 * <p>Cada degradación queda registrada en {@link EvaluacionDeFraude#advertencias()}, de modo
 * que una decisión tomada a ciegas es distinguible de una tomada con cobertura completa.
 */
public final class EvaluadorDeFraudeService implements EvaluarTransaccionUseCase, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(EvaluadorDeFraudeService.class);

    private final MotorDeReglas motor;
    private final RepositorioDePerfiles perfiles;
    private final ContadorDeVelocidad contadores;
    private final RegistroDeAuditoria auditoria;
    private final PublicadorDeDecisiones publicador;
    private final ConfiguracionDelEvaluador configuracion;
    private final Clock reloj;
    private final ExecutorService ejecutor;

    public EvaluadorDeFraudeService(
            MotorDeReglas motor,
            RepositorioDePerfiles perfiles,
            ContadorDeVelocidad contadores,
            RegistroDeAuditoria auditoria,
            PublicadorDeDecisiones publicador,
            ConfiguracionDelEvaluador configuracion,
            Clock reloj) {
        this.motor = Invariantes.noNulo(motor, "motor");
        this.perfiles = Invariantes.noNulo(perfiles, "repositorio de perfiles");
        this.contadores = Invariantes.noNulo(contadores, "contador de velocidad");
        this.auditoria = Invariantes.noNulo(auditoria, "registro de auditoría");
        this.publicador = Invariantes.noNulo(publicador, "publicador de decisiones");
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

        Future<PerfilDeComportamiento> futuroPerfil = ejecutor.submit(() -> cargarPerfil(transaccion, evaluadoEn));
        Future<Map<ClaveDeVelocidad, Long>> futuroContadores = ejecutor.submit(() -> resolverContadores(claves));

        PerfilDeComportamiento perfil = esperar(
                futuroPerfil,
                inicioNanos,
                "perfil de comportamiento",
                advertencias,
                () -> perfilVacioPara(transaccion, evaluadoEn));

        Map<ClaveDeVelocidad, Long> valores =
                esperar(futuroContadores, inicioNanos, "contadores de velocidad", advertencias, Map::of);

        ContextoDeEvaluacion contexto =
                new ContextoDeEvaluacion(transaccion, perfil, valores, configuracion.politica(), evaluadoEn);
        ResultadoDelMotor resultado = motor.ejecutar(contexto);
        advertencias.addAll(resultado.advertencias());

        EvaluacionDeFraude evaluacion = EvaluacionDeFraude.apartirDe(
                transaccion.id(),
                resultado.senales(),
                List.copyOf(advertencias),
                evaluadoEn,
                Duration.ofNanos(System.nanoTime() - inicioNanos));

        registrar(evaluacion, transaccion);
        publicar(evaluacion, transaccion);

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

    private PerfilDeComportamiento cargarPerfil(Transaccion transaccion, Instant evaluadoEn) {
        return perfiles.buscarPor(transaccion.cliente())
                // Un perfil de otro cliente significa un error de clave en el adaptador. Es
                // preferible evaluar a ciegas que evaluar contra la normalidad de un tercero.
                .filter(perfil -> perfil.cliente().equals(transaccion.cliente()))
                .orElseGet(() -> perfilVacioPara(transaccion, evaluadoEn));
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
    // Difusión del resultado
    // -------------------------------------------------------------------------------------

    /**
     * Auditar y publicar ocurren después de que la decisión esté tomada, y por eso ninguno de
     * los dos puede propagar. Que no se haya podido escribir el rastro no cambia el veredicto
     * que ya se comunicó al autorizador; lo que hace falta es que salte una alarma, no que se
     * caiga la transacción.
     *
     * <p>Siguen en el camino caliente en esta iteración. Cuando existan los adaptadores pasarán
     * a un outbox transaccional para sacarlos del presupuesto de latencia.
     */
    private void registrar(EvaluacionDeFraude evaluacion, Transaccion transaccion) {
        try {
            auditoria.registrar(evaluacion);
        } catch (RuntimeException e) {
            log.error(
                    "No se pudo auditar la decisión {} de {}: {}",
                    evaluacion.decision(),
                    transaccion.descripcionSegura(),
                    e.toString());
        }
    }

    private void publicar(EvaluacionDeFraude evaluacion, Transaccion transaccion) {
        try {
            publicador.publicar(evaluacion);
        } catch (RuntimeException e) {
            log.error(
                    "No se pudo publicar la decisión {} de {}: {}",
                    evaluacion.decision(),
                    transaccion.descripcionSegura(),
                    e.toString());
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
