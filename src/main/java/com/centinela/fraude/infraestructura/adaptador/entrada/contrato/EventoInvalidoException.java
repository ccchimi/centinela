package com.centinela.fraude.infraestructura.adaptador.entrada.contrato;

import java.io.Serial;

/**
 * El mensaje llegó bien formado pero su contenido no se puede convertir en una transacción
 * evaluable: falta un campo, un código no existe o viola una invariante del dominio.
 *
 * <p>Es <strong>no reintentable</strong> por definición, y así se registra en el manejador de
 * errores. Reintentar un mensaje que siempre va a fallar solo consigue bloquear la partición
 * durante todo el backoff y retrasar las transacciones que vienen detrás, que sí son válidas.
 * Va directo a la cola de descartes.
 */
public class EventoInvalidoException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public EventoInvalidoException(String mensaje) {
        super(mensaje);
    }

    public EventoInvalidoException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
