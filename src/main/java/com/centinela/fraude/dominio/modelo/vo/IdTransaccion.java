package com.centinela.fraude.dominio.modelo.vo;

import com.centinela.fraude.dominio.compartido.Invariantes;

/**
 * Identificador de la transacción, tal y como lo emite el sistema de autorización de origen.
 *
 * <p>Se modela sobre {@code String} y no sobre {@code UUID} a propósito: el identificador nos
 * llega de sistemas externos (switches de tarjeta, pasarelas) que usan ULID, UUID o secuencias
 * propias. Forzar UUID nos obligaría a inventar una traducción y perderíamos la trazabilidad
 * extremo a extremo con el emisor.
 */
public record IdTransaccion(String valor) implements Comparable<IdTransaccion> {

    private static final int LONGITUD_MAXIMA = 64;

    public IdTransaccion {
        valor = Invariantes.textoConContenido(valor, "idTransaccion", LONGITUD_MAXIMA);
        Invariantes.exigir(
                valor.chars().allMatch(IdTransaccion::esCaracterAdmitido),
                "'idTransaccion' solo admite caracteres alfanuméricos, '-' y '_'");
    }

    public static IdTransaccion de(String valor) {
        return new IdTransaccion(valor);
    }

    private static boolean esCaracterAdmitido(int caracter) {
        return Character.isLetterOrDigit(caracter) || caracter == '-' || caracter == '_';
    }

    @Override
    public int compareTo(IdTransaccion otro) {
        return valor.compareTo(otro.valor);
    }

    @Override
    public String toString() {
        return valor;
    }
}
