package com.centinela.fraude.dominio.excepcion;

/**
 * Un objeto de dominio se intentó construir en un estado imposible.
 *
 * <p>Semánticamente equivale a un 400: el emisor mandó datos que el modelo no admite. Se lanza
 * sin traza de pila a propósito (ver {@link ErrorDeDominio}), porque en un motor de fraude la
 * entrada malformada es un caso frecuente, no excepcional.
 */
public final class ViolacionDeInvariante extends ErrorDeDominio {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public static final String CODIGO = "DOMINIO_INVARIANTE_VIOLADA";

    private ViolacionDeInvariante(String mensaje) {
        super(CODIGO, mensaje, null, false);
    }

    public static ViolacionDeInvariante de(String mensaje) {
        return new ViolacionDeInvariante(mensaje);
    }
}
