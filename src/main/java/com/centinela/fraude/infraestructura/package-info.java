/**
 * Adaptadores: todo lo que traduce entre el mundo exterior y el lenguaje del dominio.
 *
 * <p>Es la única capa que conoce Kafka, DynamoDB, ElastiCache, HTTP y Jackson. Es también la
 * única que puede cambiar por completo sin tocar una sola regla de fraude, que es la prueba de
 * que la separación está bien hecha.
 *
 * <ul>
 *   <li>{@code adaptador.entrada} — consumidor Kafka de transacciones, API REST de consulta y
 *       reevaluación, manejador global de excepciones.
 *   <li>{@code adaptador.salida} — implementaciones de los puertos de salida sobre DynamoDB,
 *       ElastiCache y el productor Kafka.
 *   <li>{@code config} — cableado de beans, clientes AWS, propiedades tipadas y las pistas de
 *       registro en tiempo de ejecución que necesita la compilación nativa.
 * </ul>
 *
 * <p><em>Pendiente en esta iteración:</em> todo el contenido. Esta primera entrega es el
 * esqueleto y el dominio.
 */
package com.centinela.fraude.infraestructura;
