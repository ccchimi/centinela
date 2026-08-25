package com.centinela.fraude.infraestructura.adaptador.salida.kafka;

import org.apache.kafka.common.serialization.Serializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

/**
 * Serializador de la cola de descartes, que tiene que tragar con dos formas distintas de basura.
 *
 * <p>Un mensaje acaba en la DLQ por dos caminos y el valor que llega aquí no es el mismo:
 *
 * <ul>
 *   <li><strong>Falló la deserialización.</strong> El {@code DeadLetterPublishingRecoverer}
 *       recupera los bytes originales de la cabecera de error y publica un {@code byte[]}. Hay
 *       que reenviarlos tal cual: son la única evidencia de qué mandó el emisor, y volver a
 *       serializarlos los destruiría.
 *   <li><strong>Falló la validación o la evaluación.</strong> El valor es el DTO ya
 *       deserializado, y lo útil es publicarlo como JSON legible.
 * </ul>
 *
 * <p>Un serializador único no cubre los dos casos, y usar solo JSON convertiría los bytes
 * originales en una cadena Base64 imposible de reproducir.
 */
public final class SerializadorDeDescartes implements Serializer<Object> {

    private final JacksonJsonSerializer<Object> json = new JacksonJsonSerializer<>();

    public SerializadorDeDescartes() {
        // Sin cabeceras de tipo: quien lea la DLQ lo hará con herramientas genéricas, y una
        // cabecera con un nombre de clase de este proyecto no le sirve de nada.
        json.setAddTypeInfo(false);
    }

    @Override
    public byte[] serialize(String topic, Object dato) {
        return switch (dato) {
            case null -> null;
            case byte[] bytes -> bytes;
            default -> json.serialize(topic, dato);
        };
    }

    @Override
    public void close() {
        json.close();
    }
}
