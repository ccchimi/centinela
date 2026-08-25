package com.centinela.fraude.infraestructura.adaptador.salida.kafka;

import java.io.Serial;

/**
 * No se pudo publicar una decisión ya tomada.
 *
 * <p>El caso de uso la captura y la registra sin abortar: el veredicto ya se comunicó al
 * autorizador y no cambia porque el broker esté caído. Lo que hace falta es que salte una
 * alarma, no que se caiga la autorización.
 */
public class PublicacionFallidaException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public PublicacionFallidaException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
