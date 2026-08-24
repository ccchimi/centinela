package com.centinela.fraude.dominio.modelo;

import com.centinela.fraude.dominio.compartido.Invariantes;
import com.centinela.fraude.dominio.modelo.vo.PuntajeDeRiesgo;

/**
 * Evidencia concreta que una regla encontró y el riesgo que aporta.
 *
 * <p>Es el mecanismo de <em>explicabilidad</em> del motor. Una decisión sin las señales que la
 * produjeron es indefendible ante el cliente, ante el analista que revisa la cola y ante el
 * supervisor: el {@code codigo} es el identificador estable con el que se documenta la regla y
 * la {@code descripcion} es lo que acaba viendo un humano.
 *
 * @param codigo identificador estable en MAYUSCULAS_CON_GUION_BAJO, p. ej. {@code VIAJE_IMPOSIBLE}
 * @param descripcion explicación legible, sin datos personales
 * @param aporte incremento de riesgo en la escala de {@link PuntajeDeRiesgo}
 */
public record SenalDeRiesgo(String codigo, String descripcion, int aporte) {

    public SenalDeRiesgo {
        codigo = Invariantes.textoConContenido(codigo, "senal.codigo", 64);
        Invariantes.exigir(
                codigo.chars().allMatch(c -> Character.isUpperCase(c) || Character.isDigit(c) || c == '_'),
                "'senal.codigo' debe usar MAYUSCULAS_CON_GUION_BAJO, pero fue '%s'",
                codigo);
        descripcion = Invariantes.textoConContenido(descripcion, "senal.descripcion", 256);
        Invariantes.enRango(aporte, 0, PuntajeDeRiesgo.MAXIMO, "senal.aporte");
    }

    public static SenalDeRiesgo de(String codigo, String descripcion, int aporte) {
        return new SenalDeRiesgo(codigo, descripcion, aporte);
    }

    @Override
    public String toString() {
        return "%s(+%d)".formatted(codigo, aporte);
    }
}
