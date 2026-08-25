package com.centinela.fraude.infraestructura;

import com.centinela.fraude.infraestructura.config.PropiedadesDeAws;
import java.util.List;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

/**
 * Crea las tablas de DynamoDB en LocalStack.
 *
 * <p>En producción las crea Terraform; aquí hace falta un equivalente mínimo para que los tests
 * tengan contra qué hablar. Es idempotente a propósito: se invoca desde cada clase de test y los
 * contenedores se reutilizan entre ellas, así que la segunda llamada encuentra las tablas ya
 * creadas y eso no es un error.
 */
public final class TablasDePrueba {

    private TablasDePrueba() {}

    public static void crear(DynamoDbAsyncClient cliente, PropiedadesDeAws propiedades) {
        crearTabla(cliente, propiedades.dynamodb().tablaPerfiles(), "idCliente");
        crearTabla(cliente, propiedades.dynamodb().tablaAuditoria(), "idTransaccion");
    }

    private static void crearTabla(DynamoDbAsyncClient cliente, String nombre, String clave) {
        CreateTableRequest peticion = CreateTableRequest.builder()
                .tableName(nombre)
                .keySchema(KeySchemaElement.builder().attributeName(clave).keyType(KeyType.HASH).build())
                .attributeDefinitions(List.of(AttributeDefinition.builder()
                        .attributeName(clave)
                        .attributeType(ScalarAttributeType.S)
                        .build()))
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .build();

        try {
            cliente.createTable(peticion).join();
        } catch (RuntimeException e) {
            if (!yaExiste(e)) {
                throw e;
            }
        }
    }

    /** El join() envuelve la causa, así que hay que recorrer la cadena. */
    private static boolean yaExiste(RuntimeException e) {
        for (Throwable causa = e; causa != null; causa = causa.getCause()) {
            if (causa instanceof ResourceInUseException) {
                return true;
            }
        }
        return false;
    }
}
