/**
 * Motor de reglas: el conocimiento de fraude propiamente dicho.
 *
 * <h2>Forma del diseño</h2>
 *
 * <pre>
 *   caso de uso                    motor                      reglas
 *   ───────────                    ─────                      ──────
 *   1. pide contadores  ────►  contadoresNecesarios()  ──►  contadoresQueNecesita()
 *   2. resuelve E/S en paralelo (perfil + contadores)
 *   3. arma ContextoDeEvaluacion
 *   4. ejecuta          ────►  ejecutar(contexto)      ──►  evaluar(contexto)  ← función pura
 *                              agrega con switch            ResultadoDeRegla (sellado)
 *                              exhaustivo
 * </pre>
 *
 * <p>El protocolo en dos fases —declarar qué datos hacen falta, y solo después evaluar— es la
 * decisión central de este paquete. Concentra toda la E/S en un punto donde se puede
 * paralelizar y acotar con un presupuesto de latencia, y deja las reglas como funciones puras
 * que se prueban sin mocks y se pueden reejecutar sobre datos históricos para recalibrar.
 *
 * <h2>Agnóstico por construcción</h2>
 *
 * Ni una anotación, ni un log, ni una dependencia fuera del JDK. Verificado en
 * {@code ArquitecturaHexagonalTest}.
 */
package com.centinela.fraude.dominio.regla;
