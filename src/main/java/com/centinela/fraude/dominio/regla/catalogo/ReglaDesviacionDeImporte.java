package com.centinela.fraude.dominio.regla.catalogo;

import com.centinela.fraude.dominio.modelo.PerfilDeComportamiento;
import com.centinela.fraude.dominio.modelo.vo.Dinero;
import com.centinela.fraude.dominio.regla.ContextoDeEvaluacion;
import com.centinela.fraude.dominio.regla.PoliticaDeRiesgo;
import com.centinela.fraude.dominio.regla.ReglaDeFraude;
import com.centinela.fraude.dominio.regla.ResultadoDeRegla;
import java.math.BigDecimal;

/**
 * El importe se sale del gasto habitual del titular.
 *
 * <p>Escalonada en tres tramos en vez de un umbral único: gastar cinco veces la media es raro,
 * gastar veinte veces es otra cosa, y tratarlos igual desperdicia información. Los factores
 * salen de {@link PoliticaDeRiesgo} porque son lo que más se recalibra.
 *
 * <p>Se degrada, no se dispara, cuando el perfil no tiene historial suficiente: la media de tres
 * operaciones no describe a nadie, y disparar sobre ella penalizaría a todo cliente nuevo, que
 * es el error más caro que puede cometer un motor de fraude en producción.
 */
public final class ReglaDesviacionDeImporte implements ReglaDeFraude {

    public static final String CODIGO = "DESVIACION_DE_IMPORTE";

    private static final int APORTE_MODERADA = 120;
    private static final int APORTE_ALTA = 220;
    private static final int APORTE_EXTREMA = 340;

    @Override
    public String codigo() {
        return CODIGO;
    }

    @Override
    public ResultadoDeRegla evaluar(ContextoDeEvaluacion contexto) {
        PerfilDeComportamiento perfil = contexto.perfil();
        Dinero importe = contexto.transaccion().importe();

        if (!perfil.esFiable()) {
            return ResultadoDeRegla.degradada(
                    "el perfil solo acumula %d operaciones, insuficientes para una media significativa"
                            .formatted(perfil.transaccionesObservadas()));
        }
        if (perfil.importeMedio().esCero()) {
            return ResultadoDeRegla.degradada("el perfil no tiene importe medio calculado");
        }
        // Comparar euros contra la media en dólares daría un número sin sentido. El puerto de
        // perfiles normalizará la moneda; hasta entonces la regla se declara degradada en vez
        // de dejar que Dinero lance dentro del motor.
        if (!importe.moneda().equals(perfil.importeMedio().moneda())) {
            return ResultadoDeRegla.degradada(
                    "la transacción es en %s y el perfil está en %s"
                            .formatted(importe.moneda().getCurrencyCode(), perfil.importeMedio().moneda().getCurrencyCode()));
        }

        BigDecimal razon = perfil.desviacionSobreLaMedia(importe);
        PoliticaDeRiesgo politica = contexto.politica();

        int aporteBase;
        String tramo;
        if (razon.compareTo(politica.factorDesviacionExtrema()) >= 0) {
            aporteBase = APORTE_EXTREMA;
            tramo = "extrema";
        } else if (razon.compareTo(politica.factorDesviacionAlta()) >= 0) {
            aporteBase = APORTE_ALTA;
            tramo = "alta";
        } else if (razon.compareTo(politica.factorDesviacionModerada()) >= 0) {
            aporteBase = APORTE_MODERADA;
            tramo = "moderada";
        } else {
            return ResultadoDeRegla.noDisparada();
        }

        return ResultadoDeRegla.disparada(
                CODIGO,
                "Importe %s frente a una media de %s: desviación %s (x%s)"
                        .formatted(importe, perfil.importeMedio(), tramo, razon.stripTrailingZeros().toPlainString()),
                contexto.politica().aporteDe(CODIGO, aporteBase));
    }
}
