package com.centinela.fraude.dominio.regla.catalogo;

import com.centinela.fraude.dominio.modelo.vo.Comercio;
import com.centinela.fraude.dominio.regla.ContextoDeEvaluacion;
import com.centinela.fraude.dominio.regla.ReglaDeFraude;
import com.centinela.fraude.dominio.regla.ResultadoDeRegla;

/**
 * El comercio pertenece a una categoría con tasa de contracargo históricamente alta.
 *
 * <p>Peso bajo a propósito. Apostar o comprar criptoactivos es legal y frecuente; esta señal
 * solo tiene sentido como modificador que empuja a otra evidencia por encima del umbral, nunca
 * como motivo de rechazo. Ponerle un peso alto convertiría el motor en un filtro moral, que es
 * un modo conocido de perder clientes legítimos.
 *
 * <p>Se atenúa si el titular ya opera habitualmente con ese comercio: el jugador recurrente no
 * es sospechoso por serlo.
 */
public final class ReglaComercioDeAltoRiesgo implements ReglaDeFraude {

    public static final String CODIGO = "COMERCIO_ALTO_RIESGO";
    private static final int APORTE_POR_DEFECTO = 90;

    @Override
    public String codigo() {
        return CODIGO;
    }

    @Override
    public ResultadoDeRegla evaluar(ContextoDeEvaluacion contexto) {
        Comercio comercio = contexto.transaccion().comercio();

        if (!comercio.esDeCategoriaDeAltoRiesgo()) {
            return ResultadoDeRegla.noDisparada();
        }
        if (contexto.perfil().esComercioHabitual(comercio)) {
            return ResultadoDeRegla.noDisparada();
        }

        return ResultadoDeRegla.disparada(
                CODIGO,
                "Comercio de categoría sensible (MCC %s) con el que el titular no opera habitualmente"
                        .formatted(comercio.mcc()),
                contexto.politica().aporteDe(CODIGO, APORTE_POR_DEFECTO));
    }
}
