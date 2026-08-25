package com.centinela.fraude.infraestructura.adaptador.salida.dynamodb;

import com.centinela.fraude.dominio.modelo.EvaluacionDeFraude;
import com.centinela.fraude.dominio.modelo.vo.IdTransaccion;
import com.centinela.fraude.dominio.puerto.salida.RegistroDeAuditoria;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

/**
 * Adaptador de {@link RegistroDeAuditoria} sobre DynamoDB, con TTL.
 *
 * <p>La escritura es un {@code PutItem} sin condición: es idempotente por clave, así que
 * reprocesar el mismo mensaje tras un reinicio sobrescribe el item con un contenido equivalente
 * en vez de duplicarlo. Es exactamente lo que se quiere con entrega al-menos-una-vez.
 *
 * <p>El TTL lo calcula {@link EvaluacionDynamo} a partir de la retención configurada. Delegar el
 * borrado en DynamoDB es la única forma sensata de cumplir una retención regulatoria de siete
 * años sin mantener un proceso de purga propio.
 */
public final class RegistroDeAuditoriaDynamoDb implements RegistroDeAuditoria {

    private final DynamoDbAsyncClient cliente;
    private final String tabla;
    private final Duration retencion;

    public RegistroDeAuditoriaDynamoDb(DynamoDbAsyncClient cliente, String tabla, Duration retencion) {
        this.cliente = cliente;
        this.tabla = tabla;
        this.retencion = retencion;
    }

    @Override
    public void registrar(EvaluacionDeFraude evaluacion) {
        PutItemRequest peticion = PutItemRequest.builder()
                .tableName(tabla)
                .item(EvaluacionDynamo.aItem(evaluacion, retencion))
                .build();

        cliente.putItem(peticion).join();
    }

    @Override
    public Optional<EvaluacionDeFraude> buscarPor(IdTransaccion transaccion) {
        GetItemRequest peticion = GetItemRequest.builder()
                .tableName(tabla)
                .key(EvaluacionDynamo.clavePara(transaccion))
                // Aquí sí, consistencia fuerte: quien consulta el rastro de auditoría suele
                // hacerlo justo después de la decisión (un analista, una reclamación), y leer
                // "no existe" para algo que se acaba de escribir sería inaceptable.
                .consistentRead(true)
                .build();

        Map<String, AttributeValue> item = cliente.getItem(peticion).join().item();
        return item == null || item.isEmpty() ? Optional.empty() : Optional.of(EvaluacionDynamo.aDominio(item));
    }
}
