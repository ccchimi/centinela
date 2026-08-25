package com.centinela.fraude.infraestructura.adaptador.salida.dynamodb;

import com.centinela.fraude.dominio.modelo.EvaluacionDeFraude;
import com.centinela.fraude.dominio.puerto.salida.BandejaDeSalida;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

/**
 * Lado de lectura del outbox sobre DynamoDB.
 *
 * <p>Consulta el índice disperso descrito en {@link BandejaDynamo}: sólo las entradas pendientes
 * están en él, así que el coste de cada ciclo del relevo es proporcional a lo que queda por
 * publicar y no al histórico completo de decisiones.
 */
public final class BandejaDeSalidaDynamoDb implements BandejaDeSalida {

    private final DynamoDbAsyncClient cliente;
    private final String tabla;
    private final Clock reloj;

    public BandejaDeSalidaDynamoDb(DynamoDbAsyncClient cliente, String tabla, Clock reloj) {
        this.cliente = cliente;
        this.tabla = tabla;
        this.reloj = reloj;
    }

    @Override
    public List<Pendiente> pendientes(int limite) {
        QueryRequest peticion = QueryRequest.builder()
                .tableName(tabla)
                .indexName(BandejaDynamo.INDICE_PENDIENTES)
                .keyConditionExpression("#estado = :pendiente")
                .expressionAttributeNames(Map.of("#estado", BandejaDynamo.ESTADO))
                .expressionAttributeValues(Map.of(":pendiente", Atributos.texto(BandejaDynamo.PENDIENTE)))
                // Ascendente por creadoEn: las más antiguas primero, para que un atasco no deje
                // entradas viejas rezagadas mientras las nuevas van saliendo.
                .scanIndexForward(true)
                .limit(limite)
                .build();

        return cliente.query(peticion).join().items().stream()
                .map(item -> new Pendiente(BandejaDynamo.aDominio(item), BandejaDynamo.intentosDe(item)))
                .toList();
    }

    @Override
    public void marcarPublicada(EvaluacionDeFraude evaluacion) {
        // REMOVE del atributo de estado, no un SET a "PUBLICADO": es lo que saca la entrada del
        // índice disperso. Marcarla con otro valor la dejaría dentro para siempre.
        UpdateItemRequest peticion = UpdateItemRequest.builder()
                .tableName(tabla)
                .key(BandejaDynamo.clavePara(evaluacion))
                .updateExpression("REMOVE #estado SET #publicadoEn = :ahora")
                .expressionAttributeNames(Map.of(
                        "#estado", BandejaDynamo.ESTADO,
                        "#publicadoEn", BandejaDynamo.atributoPublicadoEn()))
                .expressionAttributeValues(Map.of(":ahora", Atributos.momento(reloj.instant())))
                .build();

        cliente.updateItem(peticion).join();
    }

    @Override
    public void registrarIntentoFallido(EvaluacionDeFraude evaluacion, String motivo) {
        // ADD sobre el contador en vez de leer-modificar-escribir: es atómico y no necesita
        // haber leído el valor anterior, que es lo que permite que dos relevos concurrentes
        // no se pisen la cuenta.
        UpdateItemRequest peticion = UpdateItemRequest.builder()
                .tableName(tabla)
                .key(BandejaDynamo.clavePara(evaluacion))
                .updateExpression("ADD #intentos :uno SET #error = :motivo")
                .expressionAttributeNames(Map.of(
                        "#intentos", BandejaDynamo.atributoIntentos(),
                        "#error", BandejaDynamo.atributoUltimoError()))
                .expressionAttributeValues(Map.of(
                        ":uno", AttributeValue.fromN("1"),
                        ":motivo", Atributos.texto(recortar(motivo))))
                .build();

        cliente.updateItem(peticion).join();
    }

    /** El mensaje de error entra en el item; una traza larga engordaría la fila sin aportar. */
    private static String recortar(String motivo) {
        String limpio = motivo == null || motivo.isBlank() ? "sin detalle" : motivo;
        return limpio.length() <= 500 ? limpio : limpio.substring(0, 500);
    }
}
