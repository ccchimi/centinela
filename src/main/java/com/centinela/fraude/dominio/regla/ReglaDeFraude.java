package com.centinela.fraude.dominio.regla;

import com.centinela.fraude.dominio.modelo.Transaccion;
import com.centinela.fraude.dominio.modelo.vo.ClaveDeVelocidad;
import java.util.Set;

/**
 * Una regla de detección. Estrategia pura: mira el contexto y dice si encontró evidencia.
 *
 * <h2>Contrato</h2>
 *
 * <ul>
 *   <li><strong>Pura y sin E/S.</strong> Todo lo que necesita está en el
 *       {@link ContextoDeEvaluacion}. Una regla que consulte una base de datos rompe el
 *       presupuesto de latencia del motor y deja de ser reproducible.
 *   <li><strong>Determinista.</strong> Mismo contexto, mismo resultado. Sin relojes, sin
 *       aleatoriedad: el instante de evaluación viene en el contexto.
 *   <li><strong>Rápida.</strong> Se ejecutan todas en serie dentro de la ventana de
 *       autorización; ninguna debería costar más de unos microsegundos.
 *   <li><strong>Honesta con la falta de datos.</strong> Si le faltan datos para decidir debe
 *       devolver {@link ResultadoDeRegla.Degradada}, nunca "no disparada": confundirlos
 *       convierte una caída de la caché en un apagón silencioso del control.
 * </ul>
 *
 * <p>Puede lanzar: el motor aísla la excepción y la convierte en
 * {@link ResultadoDeRegla.Fallida}. Aun así, lanzar es peor que devolver {@code Degradada},
 * porque pierde el motivo estructurado.
 */
public interface ReglaDeFraude {

    /**
     * Identificador estable de la regla, en MAYUSCULAS_CON_GUION_BAJO.
     *
     * <p>Es la misma cadena con la que la política fija su peso, con la que se etiquetan las
     * métricas y con la que se documenta ante cumplimiento. No se renombra sin migración.
     */
    String codigo();

    /**
     * Contadores de velocidad que esta regla necesitará para esta transacción.
     *
     * <p>Se declaran antes de evaluar para que el caso de uso pueda pedirlos todos juntos, una
     * sola vez, fuera del camino de las reglas. Una regla que no use contadores no implementa
     * este método.
     */
    default Set<ClaveDeVelocidad> contadoresQueNecesita(Transaccion transaccion) {
        return Set.of();
    }

    /** Evalúa la transacción contra el contexto ya materializado. */
    ResultadoDeRegla evaluar(ContextoDeEvaluacion contexto);
}
