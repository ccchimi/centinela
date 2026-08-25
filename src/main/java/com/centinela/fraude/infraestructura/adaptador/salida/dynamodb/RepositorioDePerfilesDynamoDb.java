package com.centinela.fraude.infraestructura.adaptador.salida.dynamodb;

import com.centinela.fraude.dominio.modelo.PerfilDeComportamiento;
import com.centinela.fraude.dominio.modelo.vo.IdCliente;
import com.centinela.fraude.dominio.puerto.salida.RepositorioDePerfiles;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

/**
 * Adaptador de {@link RepositorioDePerfiles} sobre DynamoDB.
 *
 * <h2>Cliente asíncrono con puerto síncrono</h2>
 *
 * El puerto es síncrono y el cliente del SDK es asíncrono, así que aquí se hace {@code join()}.
 * No es un desperdicio: el caso de uso ya invoca este adaptador dentro de un hilo virtual y con
 * un presupuesto de latencia, de modo que bloquear cuesta aparcar un hilo virtual. Lo que aporta
 * el cliente asíncrono es el control fino de tiempos —{@code apiCallTimeout} acota la llamada
 * completa con sus reintentos, {@code apiCallAttemptTimeout} cada intento— sin necesidad de un
 * pool de hilos propio para vigilarlos.
 *
 * <p>Por eso no se añade aquí un tercer timeout: el {@code join()} está acotado por el
 * {@code apiCallTimeout} del cliente, y por encima está el presupuesto del caso de uso.
 *
 * <p>Las excepciones se propagan a propósito. El caso de uso sabe degradar ante un fallo de esta
 * fuente y lo hace constar en las advertencias de la evaluación; tragárselas aquí devolvería un
 * {@code Optional.empty()} indistinguible de un cliente nuevo, y eso convertiría una caída de
 * DynamoDB en "todos los clientes son nuevos" sin que nadie se entere.
 */
public final class RepositorioDePerfilesDynamoDb implements RepositorioDePerfiles {

    private static final Logger log = LoggerFactory.getLogger(RepositorioDePerfilesDynamoDb.class);

    private final DynamoDbAsyncClient cliente;
    private final String tabla;

    public RepositorioDePerfilesDynamoDb(DynamoDbAsyncClient cliente, String tabla) {
        this.cliente = cliente;
        this.tabla = tabla;
    }

    @Override
    public Optional<PerfilDeComportamiento> buscarPor(IdCliente idCliente) {
        GetItemRequest peticion = GetItemRequest.builder()
                .tableName(tabla)
                .key(PerfilDynamo.clavePara(idCliente))
                // Lectura eventualmente consistente: el perfil es un agregado estadístico y leer
                // una versión de hace unos milisegundos no cambia ninguna decisión. La lectura
                // fuertemente consistente cuesta el doble y añade latencia en el camino caliente.
                .consistentRead(false)
                .build();

        Map<String, AttributeValue> item = cliente.getItem(peticion).join().item();
        return item == null || item.isEmpty() ? Optional.empty() : Optional.of(PerfilDynamo.aDominio(item));
    }

    @Override
    public void guardar(PerfilDeComportamiento perfil) {
        PutItemRequest peticion = PutItemRequest.builder()
                .tableName(tabla)
                .item(PerfilDynamo.aItem(perfil))
                // Escritura condicionada por marca temporal: dos evaluaciones concurrentes del
                // mismo cliente llegan aquí con perfiles calculados desde la misma base, y sin
                // esta condición la segunda pisaría los agregados de la primera. Compara sobre
                // epoch millis, que ordena bien; el texto ISO no lo haría.
                .conditionExpression("attribute_not_exists(#actualizadoEn) OR #actualizadoEn < :nuevo")
                .expressionAttributeNames(Map.of("#actualizadoEn", PerfilDynamo.ACTUALIZADO_EN))
                .expressionAttributeValues(
                        Map.of(":nuevo", Atributos.momento(perfil.actualizadoEn())))
                .build();

        try {
            cliente.putItem(peticion).join();
        } catch (RuntimeException e) {
            if (esConflictoDeVersion(e)) {
                // No es un error: otra evaluación del mismo cliente escribió algo más reciente.
                // Perder esta actualización es el resultado correcto.
                log.debug("Perfil de {} ya actualizado por una evaluación más reciente", idEnmascarado(perfil));
                return;
            }
            throw e;
        }
    }

    /** El {@code join()} envuelve la causa en CompletionException, así que hay que desenvolverla. */
    private static boolean esConflictoDeVersion(RuntimeException e) {
        for (Throwable causa = e; causa != null; causa = causa.getCause()) {
            if (causa instanceof ConditionalCheckFailedException) {
                return true;
            }
        }
        return false;
    }

    private static String idEnmascarado(PerfilDeComportamiento perfil) {
        return perfil.cliente().enmascarado();
    }
}
