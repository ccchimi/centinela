package com.centinela.fraude.dominio.regla.catalogo;

import com.centinela.fraude.dominio.modelo.vo.IdDispositivo;
import com.centinela.fraude.dominio.regla.ContextoDeEvaluacion;
import com.centinela.fraude.dominio.regla.ReglaDeFraude;
import com.centinela.fraude.dominio.regla.ResultadoDeRegla;
import java.util.Optional;

/**
 * La operación llega desde un dispositivo que este titular nunca ha usado.
 *
 * <p>Indicio principal de toma de control de cuenta. Por sí solo no basta —la gente cambia de
 * móvil— pero combinado con desviación de importe o país inusual dibuja el patrón completo.
 *
 * <p>Distingue tres situaciones que se suelen confundir: el canal no reporta huella (cajero,
 * API) es <em>no aplicable</em>; el canal sí la reporta pero no llegó es <em>degradada</em>, y
 * merece una alerta porque suele significar que el SDK del cliente está roto; y el perfil sin
 * historial también es degradada, porque "no conozco este dispositivo" es trivialmente cierto
 * para todo cliente nuevo.
 */
public final class ReglaDispositivoDesconocido implements ReglaDeFraude {

    public static final String CODIGO = "DISPOSITIVO_DESCONOCIDO";
    private static final int APORTE_POR_DEFECTO = 180;

    @Override
    public String codigo() {
        return CODIGO;
    }

    @Override
    public ResultadoDeRegla evaluar(ContextoDeEvaluacion contexto) {
        if (!contexto.transaccion().canal().soportaHuellaDeDispositivo()) {
            return ResultadoDeRegla.noAplicable(
                    "el canal %s no reporta huella de dispositivo".formatted(contexto.transaccion().canal()));
        }

        Optional<IdDispositivo> dispositivo = contexto.dispositivo();
        if (dispositivo.isEmpty()) {
            return ResultadoDeRegla.degradada(
                    "el canal %s debería reportar huella de dispositivo y no llegó"
                            .formatted(contexto.transaccion().canal()));
        }
        if (!contexto.perfil().esFiable()) {
            return ResultadoDeRegla.degradada("el perfil no tiene dispositivos conocidos con los que comparar");
        }

        if (contexto.perfil().conoceElDispositivo(dispositivo.orElseThrow())) {
            return ResultadoDeRegla.noDisparada();
        }

        return ResultadoDeRegla.disparada(
                CODIGO,
                "Dispositivo nunca visto para este titular, que tiene %d conocidos"
                        .formatted(contexto.perfil().dispositivosConocidos().size()),
                contexto.politica().aporteDe(CODIGO, APORTE_POR_DEFECTO));
    }
}
