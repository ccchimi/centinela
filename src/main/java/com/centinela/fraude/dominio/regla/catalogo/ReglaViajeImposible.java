package com.centinela.fraude.dominio.regla.catalogo;

import com.centinela.fraude.dominio.modelo.Transaccion;
import com.centinela.fraude.dominio.regla.ContextoDeEvaluacion;
import com.centinela.fraude.dominio.regla.ReglaDeFraude;
import com.centinela.fraude.dominio.regla.ResultadoDeRegla;

/**
 * El titular no ha podido desplazarse desde donde operó la última vez hasta donde opera ahora.
 *
 * <p>Es la señal más difícil de falsificar del catálogo: el defraudador controla el importe, el
 * comercio y a veces el dispositivo, pero no puede estar en dos continentes a la vez. Por eso
 * lleva el peso más alto.
 *
 * <p>Falso positivo conocido: VPN y proxies corporativos. Se acepta porque su aporte por sí solo
 * no llega a la banda de rechazo; hace falta un segundo indicio.
 */
public final class ReglaViajeImposible implements ReglaDeFraude {

    public static final String CODIGO = "VIAJE_IMPOSIBLE";
    private static final int APORTE_POR_DEFECTO = 400;

    @Override
    public String codigo() {
        return CODIGO;
    }

    @Override
    public ResultadoDeRegla evaluar(ContextoDeEvaluacion contexto) {
        Transaccion transaccion = contexto.transaccion();

        if (contexto.origen().isEmpty()) {
            return ResultadoDeRegla.noAplicable("la transacción no reporta geolocalización");
        }
        if (contexto.perfil().ultimaPosicion().isEmpty() || contexto.perfil().ultimaActividad().isEmpty()) {
            return ResultadoDeRegla.degradada("el perfil no tiene posición ni momento anterior con los que comparar");
        }

        if (!contexto.perfil().implicaViajeImposible(transaccion)) {
            return ResultadoDeRegla.noDisparada();
        }

        double distancia = contexto
                .origen()
                .orElseThrow()
                .distanciaKmHasta(contexto.perfil().ultimaPosicion().orElseThrow());

        return ResultadoDeRegla.disparada(
                CODIGO,
                "Desplazamiento de %.0f km desde la última operación, no factible en el tiempo transcurrido"
                        .formatted(distancia),
                contexto.politica().aporteDe(CODIGO, APORTE_POR_DEFECTO));
    }
}
