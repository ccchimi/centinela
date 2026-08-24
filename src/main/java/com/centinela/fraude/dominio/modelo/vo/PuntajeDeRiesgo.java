package com.centinela.fraude.dominio.modelo.vo;

import com.centinela.fraude.dominio.compartido.Invariantes;

/**
 * Puntuación de riesgo de una transacción, en la escala cerrada [0, 1000].
 *
 * <p>Se elige entero y no {@code double} por dos razones: la puntuación se compara contra
 * umbrales y se persiste como parte de la auditoría de la decisión, y en ambos casos la
 * aritmética de coma flotante introduce diferencias que serían indefendibles ante un regulador
 * ("¿por qué esta operación puntuó 700.0000001?"). La escala /1000 da resolución de sobra para
 * combinar decenas de señales sin colisiones.
 */
public record PuntajeDeRiesgo(int valor) implements Comparable<PuntajeDeRiesgo> {

    public static final int MINIMO = 0;
    public static final int MAXIMO = 1000;

    public static final PuntajeDeRiesgo NULO = new PuntajeDeRiesgo(MINIMO);
    public static final PuntajeDeRiesgo MAXIMO_RIESGO = new PuntajeDeRiesgo(MAXIMO);

    public PuntajeDeRiesgo {
        Invariantes.enRango(valor, MINIMO, MAXIMO, "puntajeDeRiesgo");
    }

    public static PuntajeDeRiesgo de(int valor) {
        return new PuntajeDeRiesgo(valor);
    }

    /**
     * Suma saturada: el motor acumula los aportes de todas las señales disparadas y varias
     * señales fuertes pueden sumar más de 1000. Saturar en el techo es correcto y evita que la
     * acumulación tenga que conocer el orden en que se aplican las reglas.
     */
    public PuntajeDeRiesgo mas(int aporte) {
        Invariantes.exigir(aporte >= 0, "El aporte de una señal no puede restar riesgo: %d", aporte);
        return new PuntajeDeRiesgo(Math.min(MAXIMO, valor + aporte));
    }

    public PuntajeDeRiesgo mas(PuntajeDeRiesgo otro) {
        return mas(otro.valor);
    }

    /** Valor en [0.0, 1.0], útil para métricas y para alimentar modelos posteriores. */
    public double normalizado() {
        return (double) valor / MAXIMO;
    }

    @Override
    public int compareTo(PuntajeDeRiesgo otro) {
        return Integer.compare(valor, otro.valor);
    }

    @Override
    public String toString() {
        return "%d/%d".formatted(valor, MAXIMO);
    }
}
