package com.centinela.fraude.dominio.modelo.vo;

/**
 * Eje sobre el que se agrega un contador de velocidad.
 *
 * <p>Es un enum y no una cadena libre porque el conjunto de ejes que el motor sabe contar es
 * cerrado: cada dimensión implica una clave distinta en la caché y un coste de memoria que hay
 * que decidir conscientemente, no dejar que aparezca porque alguien escribió una cadena nueva
 * en una regla.
 */
public enum DimensionDeVelocidad {

    /** Operaciones del mismo titular. Detecta cascada de cargos tras una toma de cuenta. */
    TRANSACCIONES_POR_CLIENTE,

    /** Intentos desde el mismo dispositivo. Detecta pruebas de tarjetas robadas (card testing). */
    INTENTOS_POR_DISPOSITIVO,

    /** Tarjetas distintas vistas en el mismo dispositivo. Señal fuerte de enumeración de BIN. */
    TARJETAS_POR_DISPOSITIVO,

    /** Comercios distintos para el mismo titular. Detecta reparto rápido del saldo disponible. */
    COMERCIOS_POR_CLIENTE
}
