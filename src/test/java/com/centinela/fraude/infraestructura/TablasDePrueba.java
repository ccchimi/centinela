package com.centinela.fraude.infraestructura;

import com.centinela.fraude.infraestructura.config.PropiedadesDeAws;
import java.util.List;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

/**
 * Crea las tablas de DynamoDB en LocalStack.
 *
 * <p>En producción las crea Terraform; aquí hace falta un equivalente mínimo para que los tests
 * tengan contra qué hablar. Es idempotente a propósito: se invoca desde cada clase de test y los
 * contenedores se reutilizan entre ellas, así que la segunda llamada encuentra las tablas ya
 * creadas y eso no es un error.
 *
 * <p>La tabla de bandeja lleva el índice disperso que usa el relevo. Definirlo aquí no es un
 * detalle de test: es parte del contrato que la infraestructura real tiene que reproducir, y
 * tenerlo escrito en código ejecutable evita que el Terraform se desvíe sin que nadie lo note.
 */
public final class TablasDePrueba {

    private static final String ESTADO = "estado";
    private static final String CREADO_EN = "creadoEn";
    private static final String INDICE_PENDIENTES = "pendientes-index";

    private TablasDePrueba() {}

    public static void crear(DynamoDbAsyncClient cliente, PropiedadesDeAws propiedades) {
        crearTabla(cliente, propiedades.dynamodb().tablaPerfiles(), "idCliente");
        crearTabla(cliente, propiedades.dynamodb().tablaAuditoria(), "idTransaccion");
        crearBandeja(cliente, propiedades.dynamodb().tablaBandeja());
    }

    private static void crearTabla(DynamoDbAsyncClient cliente, String nombre, String clave) {
        ejecutar(
                cliente,
                CreateTableRequest.builder()
                        .tableName(nombre)
                        .keySchema(clave(clave, KeyType.HASH))
                        .attributeDefinitions(List.of(texto(clave)))
                        .billingMode(BillingMode.PAY_PER_REQUEST)
                        .build());
    }

    /**
     * Bandeja de salida con índice secundario global disperso.
     *
     * <p>Sólo se declaran como atributos los que participan en claves; el resto de la fila no se
     * declara porque DynamoDB no tiene esquema. Al publicar una entrada se elimina {@code estado}
     * y la fila cae sola del índice: ahí está lo disperso.
     */
    private static void crearBandeja(DynamoDbAsyncClient cliente, String nombre) {
        GlobalSecondaryIndex pendientes = GlobalSecondaryIndex.builder()
                .indexName(INDICE_PENDIENTES)
                .keySchema(clave(ESTADO, KeyType.HASH), clave(CREADO_EN, KeyType.RANGE))
                // Proyección completa: el relevo necesita la evaluación entera para publicarla,
                // y con KEYS_ONLY tendría que hacer un GetItem por cada entrada del lote.
                .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                .build();

        ejecutar(
                cliente,
                CreateTableRequest.builder()
                        .tableName(nombre)
                        .keySchema(clave("idTransaccion", KeyType.HASH))
                        .attributeDefinitions(List.of(
                                texto("idTransaccion"),
                                texto(ESTADO),
                                AttributeDefinition.builder()
                                        .attributeName(CREADO_EN)
                                        .attributeType(ScalarAttributeType.N)
                                        .build()))
                        .globalSecondaryIndexes(pendientes)
                        .billingMode(BillingMode.PAY_PER_REQUEST)
                        .build());
    }

    private static void ejecutar(DynamoDbAsyncClient cliente, CreateTableRequest peticion) {
        try {
            cliente.createTable(peticion).join();
        } catch (RuntimeException e) {
            if (!yaExiste(e)) {
                throw e;
            }
        }
    }

    private static KeySchemaElement clave(String atributo, KeyType tipo) {
        return KeySchemaElement.builder().attributeName(atributo).keyType(tipo).build();
    }

    private static AttributeDefinition texto(String atributo) {
        return AttributeDefinition.builder()
                .attributeName(atributo)
                .attributeType(ScalarAttributeType.S)
                .build();
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
