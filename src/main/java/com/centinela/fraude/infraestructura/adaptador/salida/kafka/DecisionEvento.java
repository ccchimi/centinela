package com.centinela.fraude.infraestructura.adaptador.salida.kafka;

import com.centinela.fraude.dominio.modelo.EvaluacionDeFraude;
import java.time.Instant;
import java.util.List;

/**
 * Contrato de salida del topic de decisiones.
 *
 * <p>Lo consumen el autorizador, la cola de revisión manual y el almacén analítico. Es un
 * contrato público: cambiarlo rompe a terceros, así que se versiona en el nombre del topic
 * ({@code fraude.decisiones.v1}) y no se le quitan campos.
 *
 * <p>Lleva las señales y las advertencias, no solo el veredicto. Un consumidor que solo mire
 * {@code decision} funciona; uno que necesite explicar la decisión a un cliente o filtrar las
 * evaluaciones degradadas tiene con qué.
 *
 * @param degradada atajo para los consumidores: la evaluación se tomó con menos cobertura de la
 *     nominal. Se emite como campo propio y no se deja deducir del tamaño de la lista porque es
 *     la condición que interesa alertar
 */
public record DecisionEvento(
        String idTransaccion,
        int puntaje,
        String nivel,
        String decision,
        boolean degradada,
        List<SenalEvento> senales,
        List<String> advertencias,
        Instant evaluadaEn,
        long latenciaMicros,
        String versionDePolitica) {

    public record SenalEvento(String codigo, String descripcion, int aporte) {}

    public static DecisionEvento desde(EvaluacionDeFraude evaluacion, String versionDePolitica) {
        return new DecisionEvento(
                evaluacion.transaccion().valor(),
                evaluacion.puntaje().valor(),
                evaluacion.nivel().name(),
                evaluacion.decision().name(),
                evaluacion.estaDegradada(),
                evaluacion.senales().stream()
                        .map(senal -> new SenalEvento(senal.codigo(), senal.descripcion(), senal.aporte()))
                        .toList(),
                evaluacion.advertencias(),
                evaluacion.evaluadaEn(),
                evaluacion.latencia().toNanos() / 1_000,
                versionDePolitica);
    }
}
