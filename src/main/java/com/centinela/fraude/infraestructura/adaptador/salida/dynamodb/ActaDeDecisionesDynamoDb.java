package com.centinela.fraude.infraestructura.adaptador.salida.dynamodb;

import com.centinela.fraude.dominio.modelo.EvaluacionDeFraude;
import com.centinela.fraude.dominio.modelo.vo.IdTransaccion;
import com.centinela.fraude.dominio.puerto.salida.ActaDeDecisiones;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;

/**
 * Asiento atómico de la decisión: auditoría y bandeja de salida en una sola transacción.
 *
 * <h2>Esto es el outbox de verdad</h2>
 *
 * DynamoDB soporta {@code TransactWriteItems} sobre varias tablas, así que la escritura de
 * auditoría y la anotación de "hay que publicar esto" ocurren o no ocurren juntas. Es la
 * garantía que da sentido al patrón: sin ella, entre las dos escrituras cabe una caída y vuelve
 * a existir el hueco por el que se perdía la decisión.
 *
 * <p>Escribir dos veces seguidas y confiar en que no pase nada en medio sería un outbox de
 * mentira: exactamente el mismo agujero que había antes, solo que con más código.
 *
 * <h2>Coste</h2>
 *
 * Una escritura transaccional cuesta el doble que una normal y tiene el doble de latencia. Es el
 * precio de no perder decisiones, y se paga fuera de la ventana de autorización porque lo único
 * que queda en el camino caliente es esta escritura, ya sin Kafka detrás.
 */
public final class ActaDeDecisionesDynamoDb implements ActaDeDecisiones {

    private final DynamoDbAsyncClient cliente;
    private final String tablaAuditoria;
    private final String tablaBandeja;
    private final Duration retencion;
    private final Clock reloj;

    public ActaDeDecisionesDynamoDb(
            DynamoDbAsyncClient cliente,
            String tablaAuditoria,
            String tablaBandeja,
            Duration retencion,
            Clock reloj) {
        this.cliente = cliente;
        this.tablaAuditoria = tablaAuditoria;
        this.tablaBandeja = tablaBandeja;
        this.retencion = retencion;
        this.reloj = reloj;
    }

    @Override
    public void asentar(EvaluacionDeFraude evaluacion) {
        // Sin condición en ninguno de los dos Put: ambos son idempotentes por clave, así que
        // reprocesar el mismo mensaje tras un reinicio sobrescribe con contenido equivalente en
        // lugar de duplicar. Es justo lo que hace falta con entrega al-menos-una-vez.
        TransactWriteItem auditoria = TransactWriteItem.builder()
                .put(Put.builder()
                        .tableName(tablaAuditoria)
                        .item(EvaluacionDynamo.aItem(evaluacion, retencion))
                        .build())
                .build();

        TransactWriteItem bandeja = TransactWriteItem.builder()
                .put(Put.builder()
                        .tableName(tablaBandeja)
                        .item(BandejaDynamo.aItem(evaluacion, reloj.instant()))
                        .build())
                .build();

        cliente.transactWriteItems(TransactWriteItemsRequest.builder()
                        .transactItems(List.of(auditoria, bandeja))
                        .build())
                .join();
    }

    @Override
    public Optional<EvaluacionDeFraude> buscarPor(IdTransaccion transaccion) {
        GetItemRequest peticion = GetItemRequest.builder()
                .tableName(tablaAuditoria)
                .key(EvaluacionDynamo.clavePara(transaccion))
                // Consistencia fuerte: quien consulta el rastro suele hacerlo justo después de
                // la decisión (un analista, una reclamación), y leer "no existe" para algo que
                // se acaba de escribir sería inaceptable.
                .consistentRead(true)
                .build();

        Map<String, AttributeValue> item = cliente.getItem(peticion).join().item();
        return item == null || item.isEmpty() ? Optional.empty() : Optional.of(EvaluacionDynamo.aDominio(item));
    }
}
