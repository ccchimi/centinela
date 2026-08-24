package com.centinela.fraude.dominio.puerto.entrada;

import com.centinela.fraude.dominio.modelo.EvaluacionDeFraude;
import com.centinela.fraude.dominio.modelo.Transaccion;

/**
 * Puerto de entrada del hexágono: el único punto por el que el mundo exterior pide una
 * evaluación de fraude.
 *
 * <p>Lo implementa un servicio de la capa de aplicación y lo invocan los adaptadores de
 * entrada: el consumidor de Kafka en el flujo normal y un controlador REST para consultas
 * síncronas y para la reevaluación manual desde la herramienta de analistas. Ninguno de los dos
 * aparece en esta firma, y eso es exactamente el objetivo: el núcleo no sabe si lo llama un
 * mensaje, una petición HTTP o un test.
 *
 * <h2>Contrato</h2>
 *
 * <ul>
 *   <li><strong>Total:</strong> toda transacción válida recibe una {@link EvaluacionDeFraude}.
 *       Si una fuente de datos auxiliar no responde, el motor degrada la evaluación con las
 *       señales de que dispone y lo hace constar; no propaga el fallo. En un motor en línea,
 *       una excepción equivale a bloquear una autorización legítima.
 *   <li><strong>Idempotente:</strong> evaluar dos veces la misma transacción debe producir la
 *       misma decisión, salvo que haya cambiado el estado del perfil entre ambas llamadas.
 *   <li><strong>Sin efectos visibles para el llamante:</strong> la publicación de la decisión y
 *       su persistencia ocurren a través de los puertos de salida, no en el valor devuelto.
 *   <li><strong>Sensible a la latencia:</strong> se invoca dentro de la ventana de autorización.
 *       Las implementaciones deben respetar un presupuesto de tiempo acotado.
 * </ul>
 *
 * @throws com.centinela.fraude.dominio.excepcion.ViolacionDeInvariante si la transacción llega
 *     malformada; los adaptadores deben validar antes de construirla
 */
@FunctionalInterface
public interface EvaluarTransaccionUseCase {

    /**
     * Evalúa el riesgo de fraude de una transacción y devuelve el veredicto con su evidencia.
     *
     * @param transaccion transacción ya validada y traducida al lenguaje del dominio
     * @return evaluación con puntaje, nivel, decisión y señales disparadas
     */
    EvaluacionDeFraude evaluar(Transaccion transaccion);
}
