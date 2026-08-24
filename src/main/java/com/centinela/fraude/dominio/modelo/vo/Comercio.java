package com.centinela.fraude.dominio.modelo.vo;

import com.centinela.fraude.dominio.compartido.Invariantes;
import java.util.Set;

/**
 * Comercio destinatario de la transacción.
 *
 * <p>El {@code mcc} (Merchant Category Code, ISO 18245) es un dato de cuatro dígitos que viaja
 * en el propio mensaje de autorización y es de los pocos campos que el defraudador no controla.
 * Por eso se modela como parte del identificador del comercio y no como un atributo enriquecido
 * a posteriori: si el enriquecimiento falla, el motor sigue teniendo la señal.
 */
public record Comercio(String id, String nombre, String mcc, String paisIso2) {

    /**
     * Categorías con tasa de contracargo históricamente alta: apuestas, criptoactivos, envío de
     * dinero y venta directa. No implica fraude por sí sola, es un modificador de riesgo.
     *
     * <p>Provisional: cuando entre el motor de reglas, este conjunto pasa a ser configuración
     * versionada y no una constante del modelo.
     */
    private static final Set<String> MCC_DE_ALTO_RIESGO =
            Set.of("4829", "6051", "6211", "7273", "7801", "7802", "7995", "5967", "5122", "5912");

    public Comercio {
        id = Invariantes.textoConContenido(id, "comercio.id", 64);
        nombre = Invariantes.textoConContenido(nombre, "comercio.nombre", 128);
        mcc = Invariantes.digitos(mcc, "comercio.mcc", 4);
        paisIso2 = Invariantes.paisIso2(paisIso2, "comercio.paisIso2");
    }

    public static Comercio de(String id, String nombre, String mcc, String paisIso2) {
        return new Comercio(id, nombre, mcc, paisIso2);
    }

    public boolean esDeCategoriaDeAltoRiesgo() {
        return MCC_DE_ALTO_RIESGO.contains(mcc);
    }

    public boolean estaEn(String codigoPaisIso2) {
        return paisIso2.equalsIgnoreCase(codigoPaisIso2);
    }

    @Override
    public String toString() {
        return "%s (mcc=%s, %s)".formatted(nombre, mcc, paisIso2);
    }
}
