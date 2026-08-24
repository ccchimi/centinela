package com.centinela.fraude.dominio.modelo;

import com.centinela.fraude.dominio.compartido.Invariantes;
import com.centinela.fraude.dominio.modelo.vo.IdTransaccion;
import com.centinela.fraude.dominio.modelo.vo.PuntajeDeRiesgo;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Veredicto del motor sobre una transacción, con la evidencia que lo sostiene.
 *
 * <p>Es un objeto de auditoría además de un resultado: se persiste tal cual y se publica en
 * Kafka, y es lo que se exhibe cuando alguien —cliente, analista o regulador— pregunta por qué
 * se tomó esta decisión. Por eso lleva las señales, la latencia y el instante: reconstruir a
 * posteriori el estado que produjo una decisión es imposible, hay que guardarlo.
 *
 * @param senales evidencia ordenada de mayor a menor aporte; puede estar vacía (transacción limpia)
 * @param latencia tiempo que tardó la evaluación, no el tiempo total del mensaje
 */
public record EvaluacionDeFraude(
        IdTransaccion transaccion,
        PuntajeDeRiesgo puntaje,
        NivelDeRiesgo nivel,
        Decision decision,
        List<SenalDeRiesgo> senales,
        Instant evaluadaEn,
        Duration latencia) {

    public EvaluacionDeFraude {
        Invariantes.noNulo(transaccion, "evaluacion.transaccion");
        Invariantes.noNulo(puntaje, "evaluacion.puntaje");
        Invariantes.noNulo(nivel, "evaluacion.nivel");
        Invariantes.noNulo(decision, "evaluacion.decision");
        Invariantes.noNulo(evaluadaEn, "evaluacion.evaluadaEn");
        Invariantes.noNulo(latencia, "evaluacion.latencia");
        Invariantes.exigir(!latencia.isNegative(), "La latencia no puede ser negativa: %s", latencia);

        // Copia inmutable: el llamante conserva su lista y no puede mutar la evidencia después
        // de que la decisión esté tomada. List.copyOf además rechaza nulos dentro.
        senales = List.copyOf(Invariantes.noNulo(senales, "evaluacion.senales"));

        // Coherencia interna: el nivel tiene que ser el que corresponde al puntaje. Sin esta
        // guarda, un mapeo mal escrito en un adaptador podría persistir una evaluación cuyo
        // nivel contradice su propio puntaje, y nadie lo detectaría hasta la auditoría.
        NivelDeRiesgo esperado = NivelDeRiesgo.desde(puntaje);
        Invariantes.exigir(
                nivel == esperado,
                "El nivel %s no corresponde al puntaje %s (esperado %s)",
                nivel,
                puntaje,
                esperado);
    }

    /**
     * Construye la evaluación a partir de la evidencia: acumula los aportes, deriva el nivel y
     * aplica la decisión por defecto de la banda. Es el camino que debe usar el motor de reglas;
     * el constructor canónico queda para la rehidratación desde persistencia.
     */
    public static EvaluacionDeFraude apartirDe(
            IdTransaccion transaccion, List<SenalDeRiesgo> senales, Instant evaluadaEn, Duration latencia) {

        Invariantes.noNulo(senales, "evaluacion.senales");

        PuntajeDeRiesgo puntaje = senales.stream()
                .reduce(PuntajeDeRiesgo.NULO, (acumulado, senal) -> acumulado.mas(senal.aporte()), PuntajeDeRiesgo::mas);
        NivelDeRiesgo nivel = NivelDeRiesgo.desde(puntaje);

        List<SenalDeRiesgo> ordenadas = senales.stream()
                .sorted(Comparator.comparingInt(SenalDeRiesgo::aporte).reversed())
                .toList();

        return new EvaluacionDeFraude(
                transaccion, puntaje, nivel, nivel.decisionPorDefecto(), ordenadas, evaluadaEn, latencia);
    }

    /** Aplica un endurecimiento de la decisión (lista de bloqueo, orden judicial, regla dura). */
    public EvaluacionDeFraude conDecision(Decision nuevaDecision) {
        Invariantes.noNulo(nuevaDecision, "nuevaDecision");
        return new EvaluacionDeFraude(transaccion, puntaje, nivel, nuevaDecision, senales, evaluadaEn, latencia);
    }

    public boolean bloqueada() {
        return decision.bloquea();
    }

    /** Señal de mayor aporte, la que se muestra primero al analista. */
    public Optional<SenalDeRiesgo> senalPrincipal() {
        return senales.stream().max(Comparator.comparingInt(SenalDeRiesgo::aporte));
    }

    @Override
    public String toString() {
        return "Evaluacion[tx=%s, puntaje=%s, nivel=%s, decision=%s, senales=%s, latencia=%d ms]"
                .formatted(transaccion, puntaje, nivel, decision, senales, latencia.toMillis());
    }
}
