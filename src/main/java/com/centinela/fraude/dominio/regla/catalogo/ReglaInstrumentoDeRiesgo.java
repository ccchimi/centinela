package com.centinela.fraude.dominio.regla.catalogo;

import com.centinela.fraude.dominio.modelo.vo.InstrumentoDePago;
import com.centinela.fraude.dominio.modelo.vo.InstrumentoDePago.BilleteraDigital;
import com.centinela.fraude.dominio.modelo.vo.InstrumentoDePago.Tarjeta;
import com.centinela.fraude.dominio.modelo.vo.InstrumentoDePago.TransferenciaBancaria;
import com.centinela.fraude.dominio.regla.ContextoDeEvaluacion;
import com.centinela.fraude.dominio.regla.ReglaDeFraude;
import com.centinela.fraude.dominio.regla.ResultadoDeRegla;

/**
 * El pago se ordena con un instrumento que no lleva detrás ninguna autenticación del titular.
 *
 * <h2>Por qué una tarjeta no presente NO dispara</h2>
 *
 * Es contraintuitivo y es la decisión más importante de esta regla. La tarjeta no presente es el
 * segmento donde más fraude hay, pero también es <strong>el caso base del comercio
 * electrónico</strong>: prácticamente todo el volumen de los canales remotos es no presente.
 * Emitir una señal por ella significaría sumar el mismo aporte a casi todas las transacciones,
 * lo que no es discriminar entre buenas y malas: es desplazar la distribución entera y obligar a
 * subir todos los umbrales para compensar. Una regla que dispara siempre no aporta información,
 * solo ruido en la auditoría.
 *
 * <p>El riesgo de la tarjeta no presente se captura donde sí discrimina: en el dispositivo
 * desconocido, en el país inusual, en la ráfaga y en la desviación de importe.
 *
 * <p>Quedan dos instrumentos que sí son anómalos por sí mismos: una billetera sin token de red
 * —nada garantiza que el titular estuviera delante— y una transferencia inmediata, que es
 * irreversible en cuanto se liquida y por tanto no admite recuperación posterior.
 *
 * <h2>Switch exhaustivo</h2>
 *
 * La decisión se toma con un {@code switch} sobre la jerarquía sellada, con deconstrucción de
 * registros y guardas. Si mañana se añade un instrumento de pago, este método deja de compilar y
 * obliga a decidir explícitamente si es anómalo, en lugar de caer en un {@code default} que lo
 * trataría como inofensivo.
 */
public final class ReglaInstrumentoDeRiesgo implements ReglaDeFraude {

    public static final String CODIGO = "INSTRUMENTO_DE_RIESGO";

    @Override
    public String codigo() {
        return CODIGO;
    }

    @Override
    public ResultadoDeRegla evaluar(ContextoDeEvaluacion contexto) {
        InstrumentoDePago instrumento = contexto.transaccion().instrumento();

        return switch (instrumento) {
            // Caso base del canal, presente o no: el riesgo de la tarjeta lo capturan las
            // reglas de comportamiento, no esta.
            case Tarjeta _ -> ResultadoDeRegla.noDisparada();

            case BilleteraDigital(String proveedor, _, boolean tokenizada) when !tokenizada ->
                disparar(
                        contexto,
                        instrumento,
                        "Billetera %s sin token de red que respalde la autenticación".formatted(proveedor));
            case BilleteraDigital _ -> ResultadoDeRegla.noDisparada();

            case TransferenciaBancaria(_, String bic) ->
                disparar(
                        contexto,
                        instrumento,
                        "Transferencia inmediata desde %s, irreversible una vez liquidada".formatted(bic));
        };
    }

    /** El aporte sale del riesgo intrínseco del propio instrumento, salvo que la política lo fije. */
    private static ResultadoDeRegla disparar(
            ContextoDeEvaluacion contexto, InstrumentoDePago instrumento, String descripcion) {
        return ResultadoDeRegla.disparada(
                CODIGO, descripcion, contexto.politica().aporteDe(CODIGO, instrumento.riesgoIntrinseco()));
    }
}
