package com.centinela.fraude.dominio.modelo.vo;

import com.centinela.fraude.dominio.compartido.Invariantes;

/**
 * Identifica un contador de velocidad concreto: qué se cuenta y sobre qué valor.
 *
 * <p>Vive en el modelo y no dentro del puerto de salida porque las reglas la declaran sin
 * conocer quién la resolverá. Una regla dice "necesito los intentos de este dispositivo en la
 * ventana"; que eso acabe siendo un {@code INCR} con {@code EXPIRE} en ElastiCache es un detalle
 * que solo conoce el adaptador.
 *
 * @param valor identificador concreto dentro de la dimensión (id de cliente, huella, etc.)
 */
public record ClaveDeVelocidad(DimensionDeVelocidad dimension, String valor) {

    public ClaveDeVelocidad {
        Invariantes.noNulo(dimension, "claveDeVelocidad.dimension");
        valor = Invariantes.textoConContenido(valor, "claveDeVelocidad.valor", 128);
    }

    public static ClaveDeVelocidad de(DimensionDeVelocidad dimension, String valor) {
        return new ClaveDeVelocidad(dimension, valor);
    }

    public static ClaveDeVelocidad de(DimensionDeVelocidad dimension, IdCliente cliente) {
        return new ClaveDeVelocidad(dimension, Invariantes.noNulo(cliente, "cliente").valor());
    }

    public static ClaveDeVelocidad de(DimensionDeVelocidad dimension, IdDispositivo dispositivo) {
        return new ClaveDeVelocidad(dimension, Invariantes.noNulo(dispositivo, "dispositivo").huella());
    }

    @Override
    public String toString() {
        return "%s".formatted(dimension);
    }
}
