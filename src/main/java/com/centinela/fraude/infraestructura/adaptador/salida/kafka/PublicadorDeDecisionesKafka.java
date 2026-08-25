package com.centinela.fraude.infraestructura.adaptador.salida.kafka;

import com.centinela.fraude.dominio.modelo.EvaluacionDeFraude;
import com.centinela.fraude.dominio.puerto.salida.PublicadorDeDecisiones;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Adaptador de {@link PublicadorDeDecisiones} sobre Kafka.
 *
 * <h2>Se espera al acuse del broker</h2>
 *
 * {@code send()} es asíncrono, pero aquí se bloquea hasta el acuse. El motivo es la
 * confirmación del mensaje de entrada: el consumidor confirma en cuanto vuelve la evaluación, y
 * si la publicación siguiera en vuelo, una caída entre ambas cosas perdería la decisión sin que
 * nadie lo supiera. Con {@code acks=all} e idempotencia en el productor, esperar el acuse
 * convierte "publicado" en un hecho comprobado.
 *
 * <p>El coste es latencia dentro de la ventana de autorización. Es la razón por la que el README
 * lleva pendiente sacar la publicación a un outbox transaccional: entonces esta espera
 * desaparece sin perder la garantía.
 *
 * <h2>Clave de partición</h2>
 *
 * Se usa el identificador de la transacción, no el del cliente. El puerto recibe una
 * {@link EvaluacionDeFraude}, que no conoce al titular, y de todas formas para los consumidores
 * de aguas abajo lo que importa es poder deduplicar por transacción, no el orden por cliente.
 * Si algún consumidor llegara a necesitar ese orden, habría que ampliar el puerto, y eso es una
 * decisión de dominio, no algo que deba resolver este adaptador por su cuenta.
 */
public final class PublicadorDeDecisionesKafka implements PublicadorDeDecisiones {

    private final KafkaTemplate<String, Object> plantilla;
    private final String topico;
    private final Duration timeout;
    private final String versionDePolitica;

    public PublicadorDeDecisionesKafka(
            KafkaTemplate<String, Object> plantilla, String topico, Duration timeout, String versionDePolitica) {
        this.plantilla = plantilla;
        this.topico = topico;
        this.timeout = timeout;
        this.versionDePolitica = versionDePolitica;
    }

    @Override
    public void publicar(EvaluacionDeFraude evaluacion) {
        DecisionEvento evento = DecisionEvento.desde(evaluacion, versionDePolitica);
        String clave = evaluacion.transaccion().valor();

        try {
            plantilla.send(topico, clave, evento).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PublicacionFallidaException("Interrumpido al publicar la decisión de " + clave, e);
        } catch (ExecutionException | TimeoutException e) {
            throw new PublicacionFallidaException(
                    "No se pudo publicar la decisión de %s en %s".formatted(clave, topico), e);
        }
    }
}
