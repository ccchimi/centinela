package com.centinela.fraude.infraestructura.adaptador.salida.dynamodb;

import java.io.Serial;

/**
 * Un item de DynamoDB no se puede traducir al dominio: le falta un atributo obligatorio o su
 * contenido no cumple las invariantes del modelo.
 *
 * <p>Es un error de infraestructura, no de dominio: significa que la tabla contiene algo que
 * este código no escribió, normalmente por una migración a medias. Se propaga como excepción
 * porque el caso de uso ya sabe degradar ante un fallo de la fuente de perfiles; lo que no
 * puede es evaluar contra un perfil medio construido.
 */
public class ItemCorruptoException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ItemCorruptoException(String mensaje) {
        super(mensaje);
    }

    public ItemCorruptoException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
