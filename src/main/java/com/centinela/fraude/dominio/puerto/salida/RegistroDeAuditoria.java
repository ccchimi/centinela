package com.centinela.fraude.dominio.puerto.salida;

import com.centinela.fraude.dominio.modelo.EvaluacionDeFraude;
import com.centinela.fraude.dominio.modelo.vo.IdTransaccion;
import java.util.Optional;

/**
 * Puerto de salida para el rastro de auditoría de las decisiones.
 *
 * <p>Separado de {@link PublicadorDeDecisiones} a propósito, aunque hoy ambos reciban el mismo
 * objeto: publicar es notificar y puede perderse y reintentarse; auditar es un requisito
 * regulatorio con retención definida y acceso por identificador. Tienen almacén distinto
 * (DynamoDB con TTL largo), garantías distintas y motivos de cambio distintos, así que
 * fundirlos en una sola interfaz violaría segregación de interfaces.
 */
public interface RegistroDeAuditoria {

    /** Registra la evaluación de forma duradera. Idempotente por identificador de transacción. */
    void registrar(EvaluacionDeFraude evaluacion);

    /** Recupera la decisión emitida para una transacción, si existe. */
    Optional<EvaluacionDeFraude> buscarPor(IdTransaccion transaccion);
}
