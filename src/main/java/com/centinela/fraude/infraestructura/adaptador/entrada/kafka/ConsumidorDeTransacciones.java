package com.centinela.fraude.infraestructura.adaptador.entrada.kafka;

import com.centinela.fraude.dominio.modelo.EvaluacionDeFraude;
import com.centinela.fraude.infraestructura.adaptador.entrada.contrato.TraductorDeTransacciones;
import com.centinela.fraude.infraestructura.adaptador.entrada.contrato.TransaccionEvento;
import com.centinela.fraude.dominio.modelo.Transaccion;
import com.centinela.fraude.dominio.puerto.entrada.EvaluarTransaccionUseCase;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Adaptador de entrada: consume transacciones del topic y las manda a evaluar.
 *
 * <p>No contiene lógica de fraude ni de reintentos: traduce, invoca el puerto de entrada y
 * confirma. Los reintentos y la cola de descartes los gobierna el {@code DefaultErrorHandler}
 * configurado en {@code ConfiguracionDeKafka}, que es donde Spring Kafka espera encontrarlos.
 *
 * <h2>Confirmación manual</h2>
 *
 * Se confirma <em>después</em> de que la evaluación se haya auditado y publicado. Si el proceso
 * cae antes, el mensaje se reprocesa: entrega al-menos-una-vez con decisiones idempotentes por
 * identificador de transacción. Lo contrario —confirmar al recibir— convertiría cualquier
 * reinicio en autorizaciones sin evaluar.
 *
 * <p>Cuando el listener lanza, no se confirma nada: el manejador de errores reintenta y, agotados
 * los intentos, publica en la cola de descartes y confirma él.
 */
@Component
public class ConsumidorDeTransacciones {

    private static final Logger log = LoggerFactory.getLogger(ConsumidorDeTransacciones.class);

    private final EvaluarTransaccionUseCase evaluador;
    private final TraductorDeTransacciones traductor;

    public ConsumidorDeTransacciones(EvaluarTransaccionUseCase evaluador, TraductorDeTransacciones traductor) {
        this.evaluador = evaluador;
        this.traductor = traductor;
    }

    @KafkaListener(
            topics = "${centinela.kafka.topics.transacciones}",
            groupId = "${spring.kafka.consumer.group-id}",
            concurrency = "${spring.kafka.listener.concurrency}")
    public void consumir(ConsumerRecord<String, TransaccionEvento> registro, Acknowledgment confirmacion) {
        Transaccion transaccion = traductor.aDominio(registro.value());

        EvaluacionDeFraude evaluacion = evaluador.evaluar(transaccion);

        confirmacion.acknowledge();

        if (log.isDebugEnabled()) {
            log.debug(
                    "{}-{}@{} -> {} ({})",
                    registro.topic(),
                    registro.partition(),
                    registro.offset(),
                    evaluacion.decision(),
                    evaluacion.puntaje());
        }
    }
}
