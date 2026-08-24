/**
 * Capa de aplicación: orquesta los casos de uso.
 *
 * <p>Implementa los puertos de entrada del dominio coordinando puertos de salida. Aquí vive el
 * "qué pasos y en qué orden", no el "qué significa": ninguna regla de fraude se decide en esta
 * capa, solo se compone el contexto, se acota el presupuesto de latencia y se decide qué hacer
 * si una fuente de datos no responde a tiempo.
 *
 * <h2>Sin anotaciones de framework</h2>
 *
 * No hay {@code @Service}, {@code @Component} ni inyección por Spring. El cableado se hace con
 * {@code @Bean} explícitos en {@code infraestructura.config}, y esta capa se queda en Java plano
 * más SLF4J (una fachada de log, no un framework).
 *
 * <p>Es un cambio respecto a lo que decía este documento en la iteración anterior, y el motivo
 * es práctico: la lógica de degradación —qué hacer cuando el perfil no llega o los contadores
 * fallan— es la parte más difícil de probar y la que más veces evita una caída. Con la capa
 * libre de anotaciones se prueba entera con JUnit y Mockito en milisegundos, sin levantar un
 * contexto de Spring. La restricción está verificada en {@code ArquitecturaHexagonalTest}.
 */
package com.centinela.fraude.aplicacion;
