package com.centinela.fraude.infraestructura.adaptador.salida.dynamodb;

import com.centinela.fraude.dominio.modelo.EvaluacionDeFraude;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Forma del item de la bandeja de salida.
 *
 * <h2>El índice disperso</h2>
 *
 * El relevo necesita responder a "¿qué queda por publicar?" sin recorrer la tabla entera. Se
 * resuelve con un índice secundario global cuya clave de partición es {@link #ESTADO}: mientras
 * la entrada está pendiente el atributo existe y la entrada aparece en el índice; al publicarla
 * se <strong>elimina</strong> el atributo y la entrada desaparece del índice sola.
 *
 * <p>Eso es lo que hace que el índice sea disperso, y es la diferencia entre un coste
 * proporcional a lo pendiente y uno proporcional a todo lo que se ha decidido nunca. Con un
 * atributo booleano {@code publicado} el índice contendría el histórico completo.
 *
 * <p>La clave de ordenación es {@link #CREADO_EN}, de modo que la consulta devuelve las más
 * antiguas primero y un atasco no deja entradas rezagadas indefinidamente.
 */
final class BandejaDynamo {

    static final String CLAVE = "idTransaccion";
    static final String ESTADO = "estado";
    static final String CREADO_EN = "creadoEn";
    static final String INDICE_PENDIENTES = "pendientes-index";
    static final String PENDIENTE = "PENDIENTE";

    private static final String INTENTOS = "intentos";
    private static final String ULTIMO_ERROR = "ultimoError";
    private static final String PUBLICADO_EN = "publicadoEn";

    private BandejaDynamo() {}

    static Map<String, AttributeValue> clavePara(EvaluacionDeFraude evaluacion) {
        return Map.of(CLAVE, Atributos.texto(evaluacion.transaccion().valor()));
    }

    /**
     * Item pendiente. Lleva la evaluación completa embebida y no una referencia a la tabla de
     * auditoría: el contenido a publicar tiene que quedar congelado al asentarlo, o el relevo
     * publicaría lo que el registro diga después, que no tiene por qué ser lo que se decidió.
     */
    static Map<String, AttributeValue> aItem(EvaluacionDeFraude evaluacion, Instant creadoEn) {
        Map<String, AttributeValue> item = new HashMap<>(EvaluacionDynamo.aItemSinExpiracion(evaluacion));
        item.put(ESTADO, Atributos.texto(PENDIENTE));
        item.put(CREADO_EN, Atributos.momento(creadoEn));
        item.put(INTENTOS, Atributos.numero(0));
        return Map.copyOf(item);
    }

    static EvaluacionDeFraude aDominio(Map<String, AttributeValue> item) {
        return EvaluacionDynamo.aDominio(item);
    }

    static int intentosDe(Map<String, AttributeValue> item) {
        AttributeValue valor = item.get(INTENTOS);
        return valor == null ? 0 : Integer.parseInt(valor.n());
    }

    static String atributoIntentos() {
        return INTENTOS;
    }

    static String atributoUltimoError() {
        return ULTIMO_ERROR;
    }

    static String atributoPublicadoEn() {
        return PUBLICADO_EN;
    }
}
