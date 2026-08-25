package com.centinela.fraude.infraestructura.adaptador.salida.dynamodb;

import com.centinela.fraude.dominio.excepcion.ErrorDeDominio;
import com.centinela.fraude.dominio.modelo.Decision;
import com.centinela.fraude.dominio.modelo.EvaluacionDeFraude;
import com.centinela.fraude.dominio.modelo.NivelDeRiesgo;
import com.centinela.fraude.dominio.modelo.SenalDeRiesgo;
import com.centinela.fraude.dominio.modelo.vo.IdTransaccion;
import com.centinela.fraude.dominio.modelo.vo.PuntajeDeRiesgo;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/** Traducción entre {@link EvaluacionDeFraude} y el item de la tabla de auditoría. */
final class EvaluacionDynamo {

    static final String CLAVE = "idTransaccion";
    /** Atributo de expiración; es el que se declara como TTL al crear la tabla. */
    static final String EXPIRA_EN = "expiraEn";

    private static final String PUNTAJE = "puntaje";
    private static final String NIVEL = "nivel";
    private static final String DECISION = "decision";
    private static final String SENALES = "senales";
    private static final String ADVERTENCIAS = "advertencias";
    private static final String EVALUADA_EN = "evaluadaEn";
    private static final String LATENCIA_MICROS = "latenciaMicros";

    private static final String CODIGO = "codigo";
    private static final String DESCRIPCION = "descripcion";
    private static final String APORTE = "aporte";

    private EvaluacionDynamo() {}

    static Map<String, AttributeValue> clavePara(IdTransaccion transaccion) {
        return Map.of(CLAVE, Atributos.texto(transaccion.valor()));
    }

    static Map<String, AttributeValue> aItem(EvaluacionDeFraude evaluacion, Duration retencion) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(CLAVE, Atributos.texto(evaluacion.transaccion().valor()));
        item.put(PUNTAJE, Atributos.numero(evaluacion.puntaje().valor()));
        item.put(NIVEL, Atributos.texto(evaluacion.nivel().name()));
        item.put(DECISION, Atributos.texto(evaluacion.decision().name()));
        item.put(SENALES, Atributos.lista(evaluacion.senales(), EvaluacionDynamo::aSenal));
        item.put(ADVERTENCIAS, Atributos.listaDeTextos(evaluacion.advertencias()));
        item.put(EVALUADA_EN, Atributos.momento(evaluacion.evaluadaEn()));
        // Microsegundos y no milisegundos: el presupuesto de latencia son 150 ms y guardar la
        // medida redondeada a milisegundos perdería casi toda la resolución del percentil bajo.
        item.put(LATENCIA_MICROS, Atributos.numero(evaluacion.latencia().toNanos() / 1_000));
        // El TTL de DynamoDB se expresa en segundos epoch, no en millis.
        item.put(EXPIRA_EN, Atributos.numero(evaluacion.evaluadaEn().plus(retencion).getEpochSecond()));
        return Map.copyOf(item);
    }

    static EvaluacionDeFraude aDominio(Map<String, AttributeValue> item) {
        try {
            return new EvaluacionDeFraude(
                    IdTransaccion.de(Atributos.texto(item, CLAVE)),
                    PuntajeDeRiesgo.de(Atributos.numeroEntero(item, PUNTAJE)),
                    NivelDeRiesgo.valueOf(Atributos.texto(item, NIVEL)),
                    Decision.valueOf(Atributos.texto(item, DECISION)),
                    Atributos.lista(item, SENALES, valor -> aSenal(valor.m())),
                    Atributos.listaDeTextos(item, ADVERTENCIAS),
                    Atributos.momento(item, EVALUADA_EN),
                    Duration.ofNanos(Atributos.numeroLargo(item, LATENCIA_MICROS) * 1_000));
        } catch (ErrorDeDominio | IllegalArgumentException e) {
            throw new ItemCorruptoException(
                    "La evaluación almacenada no se puede reconstruir: " + e.getMessage(), e);
        }
    }

    private static AttributeValue aSenal(SenalDeRiesgo senal) {
        return Atributos.mapa(Map.of(
                CODIGO, Atributos.texto(senal.codigo()),
                DESCRIPCION, Atributos.texto(senal.descripcion()),
                APORTE, Atributos.numero(senal.aporte())));
    }

    private static SenalDeRiesgo aSenal(Map<String, AttributeValue> mapa) {
        return SenalDeRiesgo.de(
                Atributos.texto(mapa, CODIGO), Atributos.texto(mapa, DESCRIPCION), Atributos.numeroEntero(mapa, APORTE));
    }
}
