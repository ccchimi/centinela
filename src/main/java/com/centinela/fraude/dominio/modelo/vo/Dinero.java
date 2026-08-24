package com.centinela.fraude.dominio.modelo.vo;

import com.centinela.fraude.dominio.compartido.Invariantes;
import com.centinela.fraude.dominio.excepcion.ReglaDeNegocioIncumplida;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

/**
 * Importe monetario con moneda. Inmutable y cerrado bajo sus propias operaciones.
 *
 * <p><strong>Por qué se normaliza la escala en el constructor:</strong> el {@code equals} que
 * genera un record delega en {@link BigDecimal#equals(Object)}, que es sensible a la escala
 * ({@code 10.00 != 10.0}). Sin normalizar, dos importes iguales se comportarían como distintos
 * en un {@code Set} o como clave de un mapa. Al llevar todo a los decimales propios de la
 * moneda en el constructor compacto, la igualdad estructural del record pasa a ser la igualdad
 * de negocio correcta.
 *
 * <p>El redondeo es {@link RoundingMode#HALF_EVEN} (bancario): es el que no introduce sesgo
 * acumulado al agregar millones de operaciones, que es exactamente lo que hace este motor al
 * calcular medias e importes por ventana.
 */
public record Dinero(BigDecimal importe, Currency moneda) implements Comparable<Dinero> {

    public Dinero {
        Invariantes.noNulo(importe, "importe");
        Invariantes.noNulo(moneda, "moneda");
        Invariantes.exigir(importe.signum() >= 0, "El importe no puede ser negativo: %s", importe.toPlainString());

        int decimales = Math.max(moneda.getDefaultFractionDigits(), 0);
        importe = importe.setScale(decimales, RoundingMode.HALF_EVEN);
    }

    public static Dinero de(BigDecimal importe, Currency moneda) {
        return new Dinero(importe, moneda);
    }

    public static Dinero de(String importe, String codigoIsoMoneda) {
        Invariantes.textoConContenido(importe, "importe");
        Invariantes.textoConContenido(codigoIsoMoneda, "moneda");
        return new Dinero(new BigDecimal(importe), Currency.getInstance(codigoIsoMoneda.toUpperCase(java.util.Locale.ROOT)));
    }

    /** Construye desde la unidad mínima (céntimos), que es como viajan los importes en ISO 8583. */
    public static Dinero deUnidadMinima(long unidades, Currency moneda) {
        Invariantes.noNulo(moneda, "moneda");
        int decimales = Math.max(moneda.getDefaultFractionDigits(), 0);
        return new Dinero(BigDecimal.valueOf(unidades, decimales), moneda);
    }

    public static Dinero cero(Currency moneda) {
        return new Dinero(BigDecimal.ZERO, moneda);
    }

    public Dinero mas(Dinero otro) {
        exigirMismaMoneda(otro);
        return new Dinero(importe.add(otro.importe), moneda);
    }

    public Dinero menos(Dinero otro) {
        exigirMismaMoneda(otro);
        return new Dinero(importe.subtract(otro.importe), moneda);
    }

    public Dinero por(BigDecimal factor) {
        Invariantes.noNulo(factor, "factor");
        Invariantes.exigir(factor.signum() >= 0, "El factor no puede ser negativo: %s", factor.toPlainString());
        return new Dinero(importe.multiply(factor), moneda);
    }

    /**
     * Cociente entre este importe y otro, en la misma moneda. Es la base del cálculo de
     * desviación sobre el gasto habitual del cliente.
     *
     * @return razón con 4 decimales, o {@link BigDecimal#ZERO} si el divisor es cero
     */
    public BigDecimal razonRespectoA(Dinero base) {
        exigirMismaMoneda(base);
        if (base.esCero()) {
            return BigDecimal.ZERO;
        }
        return importe.divide(base.importe, 4, RoundingMode.HALF_EVEN);
    }

    public boolean esCero() {
        return importe.signum() == 0;
    }

    public boolean esMayorQue(Dinero otro) {
        return compareTo(otro) > 0;
    }

    public boolean esMayorOIgualQue(Dinero otro) {
        return compareTo(otro) >= 0;
    }

    public boolean esMenorQue(Dinero otro) {
        return compareTo(otro) < 0;
    }

    /**
     * Orden natural <em>solo</em> entre importes de la misma moneda. Comparar EUR con USD sin
     * una tasa de cambio es un error de negocio, no un empate: se rechaza en vez de devolver
     * un resultado arbitrario.
     */
    @Override
    public int compareTo(Dinero otro) {
        exigirMismaMoneda(otro);
        return importe.compareTo(otro.importe);
    }

    private void exigirMismaMoneda(Dinero otro) {
        Invariantes.noNulo(otro, "otro importe");
        if (!moneda.equals(otro.moneda)) {
            throw ReglaDeNegocioIncumplida.de(
                    "DINERO_MONEDAS_INCOMPATIBLES",
                    "No se pueden operar importes en %s y %s sin conversión explícita"
                            .formatted(moneda.getCurrencyCode(), otro.moneda.getCurrencyCode()));
        }
    }

    @Override
    public String toString() {
        return "%s %s".formatted(importe.toPlainString(), moneda.getCurrencyCode());
    }
}
