package com.centinela.fraude.dominio.puerto.salida;

import com.centinela.fraude.dominio.modelo.vo.ClaveDeVelocidad;
import java.time.Duration;

/**
 * Puerto de salida para los contadores de velocidad: cuántos eventos de una dimensión se han
 * visto en una ventana temporal reciente.
 *
 * <p>Es la primitiva que sostiene las reglas de ráfaga —"seis intentos con tarjetas distintas
 * desde el mismo dispositivo en dos minutos"—, el patrón de ataque más común y más caro de
 * detectar sin estado compartido. Se implementará sobre ElastiCache (Valkey/Redis), no sobre
 * DynamoDB: el acceso es de incremento con expiración y latencia de un dígito de milisegundo,
 * exactamente el perfil para el que una caché en memoria existe.
 */
public interface ContadorDeVelocidad {

    /**
     * Incrementa el contador de la clave y devuelve su valor dentro de la ventana.
     *
     * <p>Debe ser atómico: el incremento y la lectura del resultado no pueden separarse o dos
     * instancias del motor contarían de menos justo durante una ráfaga.
     *
     * @return número de eventos observados para esa clave en la ventana, incluido este
     */
    long incrementarYContar(ClaveDeVelocidad clave, Duration ventana);

    /** Consulta sin incrementar, para reglas que solo leen. */
    long contar(ClaveDeVelocidad clave, Duration ventana);
}
