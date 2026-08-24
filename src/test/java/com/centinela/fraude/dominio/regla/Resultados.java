package com.centinela.fraude.dominio.regla;

import static org.assertj.core.api.Assertions.assertThat;

import com.centinela.fraude.dominio.modelo.SenalDeRiesgo;

/**
 * Aserciones sobre {@link ResultadoDeRegla}, para que los tests de reglas digan qué comprueban y
 * no cómo lo desempaquetan.
 *
 * <p>Sin esto, cada aserción sería un {@code isInstanceOf} seguido de un cast, y la diferencia
 * entre "no disparó" y "no pudo decidir" —que es justo lo que estos tests existen para fijar—
 * quedaría enterrada en el ruido.
 */
public final class Resultados {

    private Resultados() {}

    /** Exige que la regla disparara y devuelve la señal para seguir afirmando sobre ella. */
    public static SenalDeRiesgo seDisparo(ResultadoDeRegla resultado) {
        assertThat(resultado)
                .as("se esperaba que la regla disparara, pero devolvió %s", resultado)
                .isInstanceOf(ResultadoDeRegla.Disparada.class);
        return ((ResultadoDeRegla.Disparada) resultado).senal();
    }

    public static void noSeDisparo(ResultadoDeRegla resultado) {
        assertThat(resultado)
                .as("se esperaba que la regla no disparara, pero devolvió %s", resultado)
                .isInstanceOf(ResultadoDeRegla.NoDisparada.class);
    }

    /** Exige que la regla no fuera pertinente y devuelve el motivo. */
    public static String noAplico(ResultadoDeRegla resultado) {
        assertThat(resultado)
                .as("se esperaba que la regla no aplicara, pero devolvió %s", resultado)
                .isInstanceOf(ResultadoDeRegla.NoAplicable.class);
        return ((ResultadoDeRegla.NoAplicable) resultado).motivo();
    }

    /** Exige que la regla se declarara sin datos suficientes y devuelve el motivo. */
    public static String seDegrado(ResultadoDeRegla resultado) {
        assertThat(resultado)
                .as("se esperaba que la regla se degradara, pero devolvió %s", resultado)
                .isInstanceOf(ResultadoDeRegla.Degradada.class);
        return ((ResultadoDeRegla.Degradada) resultado).motivo();
    }
}
