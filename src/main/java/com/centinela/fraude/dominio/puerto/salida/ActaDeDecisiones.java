package com.centinela.fraude.dominio.puerto.salida;

import com.centinela.fraude.dominio.modelo.EvaluacionDeFraude;
import com.centinela.fraude.dominio.modelo.vo.IdTransaccion;
import java.util.Optional;

/**
 * Asienta la decisión de forma duradera <strong>y</strong> deja anotada la obligación de
 * publicarla, en una sola operación atómica.
 *
 * <h2>Por qué las dos cosas van juntas</h2>
 *
 * En la iteración anterior esto eran dos puertos: {@code RegistroDeAuditoria} y
 * {@code PublicadorDeDecisiones}, separados invocando segregación de interfaces —auditar y
 * publicar tienen garantías y motivos de cambio distintos—. El razonamiento era correcto y el
 * resultado tenía un agujero: entre "he auditado" y "he publicado" cabe una caída, y la decisión
 * se perdía sin que nadie se enterase.
 *
 * <p>El patrón outbox lo resuelve exigiendo que el hecho y la promesa de contarlo caigan juntos
 * o no caiga ninguno. Eso obliga a que sea <em>una</em> escritura, y por tanto un solo puerto.
 * La atomicidad gana a la segregación: una interfaz algo más ancha es un problema de diseño;
 * una decisión de fraude perdida es un problema de negocio.
 *
 * <p>La publicación efectiva ya no ocurre aquí. La hace un relevo en segundo plano leyendo de
 * {@link BandejaDeSalida}, lo que además saca a Kafka del camino caliente: el caso de uso
 * responde sin esperar al acuse del broker.
 */
public interface ActaDeDecisiones {

    /**
     * Registra la evaluación y encola su publicación, atómicamente.
     *
     * <p>Idempotente por identificador de transacción: reprocesar el mismo mensaje tras un
     * reinicio sobrescribe el asiento con un contenido equivalente en lugar de duplicarlo, que
     * es justo lo que hace falta con entrega al-menos-una-vez.
     */
    void asentar(EvaluacionDeFraude evaluacion);

    /** Recupera la decisión emitida para una transacción, si existe. */
    Optional<EvaluacionDeFraude> buscarPor(IdTransaccion transaccion);
}
