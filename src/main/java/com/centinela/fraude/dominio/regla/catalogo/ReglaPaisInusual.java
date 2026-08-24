package com.centinela.fraude.dominio.regla.catalogo;

import com.centinela.fraude.dominio.modelo.vo.Geolocalizacion;
import com.centinela.fraude.dominio.regla.ContextoDeEvaluacion;
import com.centinela.fraude.dominio.regla.ReglaDeFraude;
import com.centinela.fraude.dominio.regla.ResultadoDeRegla;
import java.util.Optional;

/**
 * La operación se origina en un país que el titular no frecuenta.
 *
 * <p>Aporta un extra cuando además la operación cruza frontera hacia el comercio: comprar desde
 * un país nuevo en un comercio de ese mismo país es un viaje; comprar desde un país nuevo en un
 * comercio de un tercer país es un patrón de blanqueo o de tarjeta comprometida.
 */
public final class ReglaPaisInusual implements ReglaDeFraude {

    public static final String CODIGO = "PAIS_INUSUAL";
    private static final int APORTE_POR_DEFECTO = 120;
    private static final int EXTRA_POR_CRUCE_DE_FRONTERA = 60;

    @Override
    public String codigo() {
        return CODIGO;
    }

    @Override
    public ResultadoDeRegla evaluar(ContextoDeEvaluacion contexto) {
        Optional<Geolocalizacion> origen = contexto.origen();
        if (origen.isEmpty()) {
            return ResultadoDeRegla.noAplicable("la transacción no reporta geolocalización");
        }
        if (!contexto.perfil().esFiable()) {
            return ResultadoDeRegla.degradada("el perfil no tiene países habituales con los que comparar");
        }

        String pais = origen.orElseThrow().paisIso2();
        if (contexto.perfil().esPaisHabitual(pais)) {
            return ResultadoDeRegla.noDisparada();
        }

        boolean cruzaFrontera = contexto.transaccion().cruzaFrontera();
        int aporte = contexto.politica().aporteDe(CODIGO, APORTE_POR_DEFECTO)
                + (cruzaFrontera ? EXTRA_POR_CRUCE_DE_FRONTERA : 0);

        String detalle = cruzaFrontera
                ? "Origen en %s, país no habitual, y el comercio está en %s"
                        .formatted(pais, contexto.transaccion().comercio().paisIso2())
                : "Origen en %s, país no habitual para este titular".formatted(pais);

        return ResultadoDeRegla.disparada(CODIGO, detalle, aporte);
    }
}
