package com.centinela.fraude.dominio.modelo.vo;

import com.centinela.fraude.dominio.compartido.Invariantes;

/**
 * Identificador del titular que ordena la transacción.
 *
 * <p><strong>Dato personal.</strong> {@link #toString()} devuelve una versión enmascarada de
 * forma deliberada: en un motor de fraude los objetos de dominio acaban en logs, trazas y
 * mensajes de excepción, y el valor por defecto de un record los volcaría en claro. Para
 * persistir o publicar el identificador real hay que pedirlo explícitamente con
 * {@link #valor()}, y eso hace visible la intención en la revisión de código.
 */
public record IdCliente(String valor) {

    private static final int LONGITUD_MAXIMA = 64;
    private static final int CARACTERES_VISIBLES = 4;

    public IdCliente {
        valor = Invariantes.textoConContenido(valor, "idCliente", LONGITUD_MAXIMA);
    }

    public static IdCliente de(String valor) {
        return new IdCliente(valor);
    }

    /** Representación apta para logs: conserva los últimos dígitos para poder correlacionar. */
    public String enmascarado() {
        if (valor.length() <= CARACTERES_VISIBLES) {
            return "*".repeat(valor.length());
        }
        int ocultos = valor.length() - CARACTERES_VISIBLES;
        return "*".repeat(ocultos) + valor.substring(ocultos);
    }

    @Override
    public String toString() {
        return enmascarado();
    }
}
