package com.centinela.fraude.dominio.puerto.salida;

import com.centinela.fraude.dominio.compartido.Invariantes;
import com.centinela.fraude.dominio.modelo.EvaluacionDeFraude;
import java.util.List;

/**
 * Las decisiones asentadas que todavía no se han publicado.
 *
 * <p>Es el lado de lectura del outbox: {@link ActaDeDecisiones} las deja anotadas junto con la
 * auditoría, y un relevo en segundo plano las va sacando de aquí y publicándolas.
 *
 * <h2>Garantías</h2>
 *
 * <ul>
 *   <li><strong>Al-menos-una-vez.</strong> Nada se marca como publicado antes de que el broker
 *       acuse recibo. Si el proceso cae en medio, la entrada sigue pendiente y se reintenta.
 *   <li><strong>Orden de llegada.</strong> {@link #pendientes} devuelve las más antiguas
 *       primero, para que un atasco no deje entradas viejas rezagadas indefinidamente.
 *   <li><strong>Sin exclusión mutua.</strong> Dos relevos concurrentes pueden publicar la misma
 *       decisión. Se acepta a conciencia: la entrega ya es al-menos-una-vez y los consumidores
 *       deduplican por identificador de transacción, así que un arrendamiento por entrada
 *       añadiría complejidad para eliminar un duplicado que de todas formas hay que tolerar.
 * </ul>
 */
public interface BandejaDeSalida {

    /**
     * Decisiones pendientes de publicar, de la más antigua a la más reciente.
     *
     * @param limite tamaño máximo del lote; acota lo que un ciclo del relevo puede tardar
     */
    List<Pendiente> pendientes(int limite);

    /** Da por publicada la decisión: deja de aparecer en {@link #pendientes}. */
    void marcarPublicada(EvaluacionDeFraude evaluacion);

    /**
     * Anota que un intento falló. La entrada sigue pendiente; sólo se lleva la cuenta, que es lo
     * que permite alertar sobre una decisión que lleva demasiados intentos sin salir.
     */
    void registrarIntentoFallido(EvaluacionDeFraude evaluacion, String motivo);

    /**
     * Una decisión esperando turno.
     *
     * <p>Lleva la evaluación completa y no una referencia a la tabla de auditoría: el contenido
     * de lo que se va a publicar tiene que quedar congelado en el momento de asentarlo. Si el
     * relevo lo releyera, publicaría lo que el registro diga <em>ahora</em>, que no tiene por
     * qué ser lo que se decidió entonces.
     *
     * @param intentos publicaciones fallidas acumuladas para esta entrada
     */
    record Pendiente(EvaluacionDeFraude evaluacion, int intentos) {

        public Pendiente {
            Invariantes.noNulo(evaluacion, "pendiente.evaluacion");
            Invariantes.exigir(intentos >= 0, "pendiente.intentos no puede ser negativo: %d", intentos);
        }
    }
}
