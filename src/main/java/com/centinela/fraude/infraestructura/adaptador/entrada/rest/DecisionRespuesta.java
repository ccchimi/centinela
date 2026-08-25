package com.centinela.fraude.infraestructura.adaptador.entrada.rest;

import com.centinela.fraude.dominio.modelo.EvaluacionDeFraude;
import java.time.Instant;
import java.util.List;

/**
 * Representación REST de una decisión.
 *
 * <p>Es un contrato distinto del que viaja por Kafka aunque hoy se parezcan: uno lo consume el
 * autorizador en línea y el otro los sistemas analíticos, y evolucionarán a ritmos diferentes.
 * Compartir un único DTO ataría las dos audiencias.
 */
public record DecisionRespuesta(
        String idTransaccion,
        int puntaje,
        String nivel,
        String decision,
        boolean bloqueada,
        boolean degradada,
        List<SenalRespuesta> senales,
        List<String> advertencias,
        Instant evaluadaEn,
        long latenciaMicros) {

    public record SenalRespuesta(String codigo, String descripcion, int aporte) {}

    public static DecisionRespuesta desde(EvaluacionDeFraude evaluacion) {
        return new DecisionRespuesta(
                evaluacion.transaccion().valor(),
                evaluacion.puntaje().valor(),
                evaluacion.nivel().name(),
                evaluacion.decision().name(),
                evaluacion.bloqueada(),
                evaluacion.estaDegradada(),
                evaluacion.senales().stream()
                        .map(senal -> new SenalRespuesta(senal.codigo(), senal.descripcion(), senal.aporte()))
                        .toList(),
                evaluacion.advertencias(),
                evaluacion.evaluadaEn(),
                evaluacion.latencia().toNanos() / 1_000);
    }
}
