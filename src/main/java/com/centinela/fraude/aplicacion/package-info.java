/**
 * Capa de aplicación: orquesta los casos de uso.
 *
 * <p>Implementa los puertos de entrada del dominio coordinando puertos de salida. Aquí vive el
 * "qué pasos y en qué orden", no el "qué significa": ninguna regla de fraude se decide en esta
 * capa, solo se compone la evaluación, se marca el presupuesto de latencia y se decide qué
 * hacer si una fuente de datos no responde a tiempo.
 *
 * <p>Puede depender de Spring para la anotación de estereotipo y la inyección, pero no de
 * Kafka, del SDK de AWS ni de nada que hable de transporte o de persistencia concreta.
 *
 * <p><em>Pendiente en esta iteración:</em> {@code EvaluadorDeFraudeService}, el motor de reglas
 * y la ejecución concurrente de reglas independientes sobre virtual threads.
 */
package com.centinela.fraude.aplicacion;
