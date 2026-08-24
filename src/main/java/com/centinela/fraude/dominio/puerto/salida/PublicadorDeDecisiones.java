package com.centinela.fraude.dominio.puerto.salida;

import com.centinela.fraude.dominio.modelo.EvaluacionDeFraude;

/**
 * Puerto de salida para difundir el veredicto a los consumidores aguas abajo: el autorizador,
 * la cola de revisión manual, el almacén analítico y el reentrenamiento de modelos.
 *
 * <p>Se implementará sobre un productor Kafka transaccional. El núcleo no conoce topics ni
 * particiones; publica una decisión y se desentiende del transporte.
 */
public interface PublicadorDeDecisiones {

    /**
     * Publica la evaluación.
     *
     * <p>Se espera semántica al-menos-una-vez con clave estable por transacción, de modo que los
     * consumidores puedan deduplicar. Un fallo de publicación no debe abortar la evaluación ya
     * tomada: se registra y se reintenta, porque la decisión ya se comunicó al autorizador.
     */
    void publicar(EvaluacionDeFraude evaluacion);
}
