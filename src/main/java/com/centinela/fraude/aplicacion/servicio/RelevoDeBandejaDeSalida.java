package com.centinela.fraude.aplicacion.servicio;

import com.centinela.fraude.dominio.compartido.Invariantes;
import com.centinela.fraude.dominio.puerto.salida.BandejaDeSalida;
import com.centinela.fraude.dominio.puerto.salida.PublicadorDeDecisiones;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Saca de la bandeja de salida las decisiones asentadas y las publica.
 *
 * <p>Es la segunda mitad del patrón outbox. La primera —asentar la decisión y su obligación de
 * publicarla en una sola escritura atómica— la hace el caso de uso; esta se limita a cumplir esa
 * obligación, y puede fallar y reintentar tantas veces como haga falta sin que nadie pierda
 * nada, porque la anotación sigue ahí hasta que el broker acusa recibo.
 *
 * <h2>Por qué esto en vez de publicar directamente</h2>
 *
 * Antes el caso de uso publicaba en Kafka y confirmaba el mensaje de entrada acto seguido. Si la
 * publicación fallaba, se registraba el error y se confirmaba igualmente: la decisión se perdía
 * en silencio. Y cuando funcionaba, la espera del acuse del broker caía dentro de la ventana de
 * autorización. El outbox arregla las dos cosas a la vez.
 *
 * <h2>Sin anotaciones</h2>
 *
 * No sabe qué lo despierta. En producción lo hace un planificador de Spring; en un test, una
 * llamada directa a {@link #ejecutarCiclo()}, que devuelve lo que hizo y por tanto se puede
 * afirmar sobre ello sin esperas ni sondeos.
 */
public final class RelevoDeBandejaDeSalida {

    private static final Logger log = LoggerFactory.getLogger(RelevoDeBandejaDeSalida.class);

    private final BandejaDeSalida bandeja;
    private final PublicadorDeDecisiones publicador;
    private final int tamanoDeLote;
    private final int intentosAntesDeAlertar;

    public RelevoDeBandejaDeSalida(
            BandejaDeSalida bandeja,
            PublicadorDeDecisiones publicador,
            int tamanoDeLote,
            int intentosAntesDeAlertar) {
        this.bandeja = Invariantes.noNulo(bandeja, "bandeja de salida");
        this.publicador = Invariantes.noNulo(publicador, "publicador");
        Invariantes.exigir(tamanoDeLote > 0, "El tamaño de lote debe ser positivo: %d", tamanoDeLote);
        Invariantes.exigir(
                intentosAntesDeAlertar > 0, "Los intentos antes de alertar deben ser positivos: %d", intentosAntesDeAlertar);
        this.tamanoDeLote = tamanoDeLote;
        this.intentosAntesDeAlertar = intentosAntesDeAlertar;
    }

    /**
     * Publica un lote de decisiones pendientes.
     *
     * <p>Una entrada que falla no interrumpe el lote: se anota el intento y se sigue con la
     * siguiente. Lo contrario dejaría que una sola decisión problemática bloqueara la cola
     * entera, que es exactamente el fallo que este patrón existe para evitar.
     *
     * @return recuento de lo ocurrido, para métricas y para que los tests afirmen sin sondear
     */
    public Resultado ejecutarCiclo() {
        List<BandejaDeSalida.Pendiente> lote;
        try {
            lote = bandeja.pendientes(tamanoDeLote);
        } catch (RuntimeException e) {
            log.error("No se pudo leer la bandeja de salida: {}", e.toString());
            return new Resultado(0, 0);
        }

        int publicadas = 0;
        int fallidas = 0;

        for (BandejaDeSalida.Pendiente pendiente : lote) {
            try {
                publicador.publicar(pendiente.evaluacion());
                bandeja.marcarPublicada(pendiente.evaluacion());
                publicadas++;
            } catch (RuntimeException e) {
                fallidas++;
                anotarFallo(pendiente, e);
            }
        }

        if (publicadas > 0 || fallidas > 0) {
            log.debug("Relevo del outbox: {} publicadas, {} fallidas", publicadas, fallidas);
        }
        return new Resultado(publicadas, fallidas);
    }

    private void anotarFallo(BandejaDeSalida.Pendiente pendiente, RuntimeException causa) {
        int intentos = pendiente.intentos() + 1;
        try {
            bandeja.registrarIntentoFallido(pendiente.evaluacion(), causa.toString());
        } catch (RuntimeException e) {
            log.warn("Tampoco se pudo anotar el intento fallido: {}", e.toString());
        }

        // Un fallo aislado es ruido; una decisión que lleva muchos intentos sin salir es un
        // incidente, porque significa que alguien aguas abajo lleva ese tiempo sin enterarse.
        if (intentos >= intentosAntesDeAlertar) {
            log.error(
                    "La decisión de {} lleva {} intentos de publicación fallidos: {}",
                    pendiente.evaluacion().transaccion(),
                    intentos,
                    causa.toString());
        } else {
            log.warn(
                    "Fallo al publicar la decisión de {} (intento {}): {}",
                    pendiente.evaluacion().transaccion(),
                    intentos,
                    causa.toString());
        }
    }

    /** Qué hizo un ciclo del relevo. */
    public record Resultado(int publicadas, int fallidas) {

        public boolean huboTrabajo() {
            return publicadas > 0 || fallidas > 0;
        }
    }
}
