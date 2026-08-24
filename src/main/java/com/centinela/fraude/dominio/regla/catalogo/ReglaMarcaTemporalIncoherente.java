package com.centinela.fraude.dominio.regla.catalogo;

import com.centinela.fraude.dominio.regla.ContextoDeEvaluacion;
import com.centinela.fraude.dominio.regla.ReglaDeFraude;
import com.centinela.fraude.dominio.regla.ResultadoDeRegla;
import java.time.Duration;
import java.time.Instant;

/**
 * La transacción dice haber ocurrido en el futuro, más allá del desfase de reloj tolerable.
 *
 * <p>Es una regla y no una validación del modelo por una razón concreta: rechazar la
 * transacción por una marca temporal rara sería peor que evaluarla. Un reloj mal sincronizado
 * en un terminal legítimo dejaría a ese comercio sin poder cobrar; en cambio, una marca
 * temporal manipulada es un intento de esquivar precisamente los controles de ventana —ráfagas
 * y viaje imposible— y merece contar como riesgo.
 *
 * <p>Por eso {@code Transaccion} no valida su propia fecha: hacerlo obligaría a leer el reloj
 * del sistema dentro del núcleo y convertiría un indicio en un rechazo.
 */
public final class ReglaMarcaTemporalIncoherente implements ReglaDeFraude {

    public static final String CODIGO = "MARCA_TEMPORAL_INCOHERENTE";
    private static final int APORTE_POR_DEFECTO = 150;

    @Override
    public String codigo() {
        return CODIGO;
    }

    @Override
    public ResultadoDeRegla evaluar(ContextoDeEvaluacion contexto) {
        Instant ocurridaEn = contexto.transaccion().ocurridaEn();
        Duration tolerancia = contexto.politica().toleranciaDeDesfaseDeReloj();
        Instant limite = contexto.evaluadoEn().plus(tolerancia);

        if (!ocurridaEn.isAfter(limite)) {
            return ResultadoDeRegla.noDisparada();
        }

        Duration adelanto = Duration.between(contexto.evaluadoEn(), ocurridaEn);
        return ResultadoDeRegla.disparada(
                CODIGO,
                "La transacción declara haber ocurrido %d s en el futuro, con una tolerancia de %d s"
                        .formatted(adelanto.toSeconds(), tolerancia.toSeconds()),
                contexto.politica().aporteDe(CODIGO, APORTE_POR_DEFECTO));
    }
}
