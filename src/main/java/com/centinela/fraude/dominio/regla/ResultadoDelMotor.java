package com.centinela.fraude.dominio.regla;

import com.centinela.fraude.dominio.compartido.Invariantes;
import com.centinela.fraude.dominio.modelo.SenalDeRiesgo;
import java.util.List;

/**
 * Lo que produce una pasada completa del motor sobre una transacción.
 *
 * <p>Además de la evidencia lleva las {@code advertencias}: qué reglas no pudieron decidir y
 * por qué. Sin eso, una evaluación con la caché caída y una evaluación limpia serían
 * indistinguibles, que es exactamente el fallo que un atacante quiere provocar.
 *
 * @param senales evidencia encontrada, en el orden en que se declararon las reglas
 * @param advertencias reglas degradadas o fallidas, ya formateadas con su código
 * @param reglasEvaluadas total de reglas que se ejecutaron
 */
public record ResultadoDelMotor(List<SenalDeRiesgo> senales, List<String> advertencias, int reglasEvaluadas) {

    public ResultadoDelMotor {
        senales = List.copyOf(Invariantes.noNulo(senales, "resultado.senales"));
        advertencias = List.copyOf(Invariantes.noNulo(advertencias, "resultado.advertencias"));
        Invariantes.exigir(reglasEvaluadas >= 0, "reglasEvaluadas no puede ser negativo");
    }

    /** El motor decidió con información incompleta. */
    public boolean estaDegradado() {
        return !advertencias.isEmpty();
    }

    public int reglasDisparadas() {
        return senales.size();
    }
}
