/**
 * Núcleo del hexágono: el modelo de fraude y los contratos con el exterior.
 *
 * <h2>Regla de dependencia</h2>
 *
 * Este paquete y sus subpaquetes <strong>no dependen de nada</strong>: ni de Spring, ni de
 * Kafka, ni del SDK de AWS, ni de Jackson, ni de {@code jakarta.*}. Solo del JDK. La regla está
 * verificada como test en {@code ArquitecturaHexagonalTest}, no como acuerdo verbal.
 *
 * <p>El motivo es concreto y no académico: las reglas de fraude son el activo que más cambia y
 * más se prueba de este sistema, y tienen que poder ejecutarse en un test unitario de
 * milisegundos, sin contexto de Spring ni contenedores. Todo lo que acople el dominio a un
 * framework se paga después, multiplicado por cada regla que se escriba.
 *
 * <h2>Subpaquetes</h2>
 *
 * <ul>
 *   <li>{@code modelo} — agregado {@code Transaccion}, resultado {@code EvaluacionDeFraude} y
 *       el vocabulario de la decisión.
 *   <li>{@code modelo.vo} — value objects: identificadores, dinero, geolocalización,
 *       instrumentos de pago. Inmutables y siempre válidos por construcción.
 *   <li>{@code puerto.entrada} — lo que el mundo puede pedirle al motor.
 *   <li>{@code puerto.salida} — lo que el motor necesita del mundo. Interfaces declaradas aquí
 *       (inversión de dependencias) e implementadas en {@code infraestructura}.
 *   <li>{@code excepcion} — jerarquía sellada de errores de dominio.
 *   <li>{@code compartido} — guardas de invariantes.
 * </ul>
 */
package com.centinela.fraude.dominio;
