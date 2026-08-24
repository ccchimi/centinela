package com.centinela.fraude.dominio.excepcion;

/**
 * La operación era sintácticamente válida pero el dominio la rechaza.
 *
 * <p>Ejemplos: sumar importes en monedas distintas, evaluar una transacción con marca de tiempo
 * futura fuera de la tolerancia de reloj, reevaluar una decisión ya firmada. Semánticamente
 * equivale a un 409/422.
 */
public final class ReglaDeNegocioIncumplida extends ErrorDeDominio {

    private ReglaDeNegocioIncumplida(String codigo, String mensaje) {
        super(codigo, mensaje, null, true);
    }

    public static ReglaDeNegocioIncumplida de(String codigo, String mensaje) {
        return new ReglaDeNegocioIncumplida(codigo, mensaje);
    }
}
