package com.centinela.fraude.dominio.modelo.vo;

import com.centinela.fraude.dominio.compartido.Invariantes;

/**
 * Huella del dispositivo desde el que se origina la transacción (fingerprint de navegador,
 * identificador de instalación móvil, número de serie del terminal POS).
 *
 * <p>Es una de las señales más predictivas del motor: un dispositivo nunca visto asociado a un
 * cliente con historial largo es un indicio fuerte de toma de control de cuenta.
 */
public record IdDispositivo(String huella) {

    private static final int LONGITUD_MAXIMA = 128;

    public IdDispositivo {
        huella = Invariantes.textoConContenido(huella, "idDispositivo", LONGITUD_MAXIMA);
    }

    public static IdDispositivo de(String huella) {
        return new IdDispositivo(huella);
    }

    /** Prefijo estable, suficiente para correlacionar en logs sin volcar la huella completa. */
    @Override
    public String toString() {
        return huella.length() <= 12 ? huella : huella.substring(0, 12) + "…";
    }
}
