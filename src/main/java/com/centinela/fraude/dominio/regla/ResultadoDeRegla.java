package com.centinela.fraude.dominio.regla;

import com.centinela.fraude.dominio.compartido.Invariantes;
import com.centinela.fraude.dominio.modelo.SenalDeRiesgo;

/**
 * Lo que una regla devuelve tras mirar una transacción.
 *
 * <p>No es un booleano ni un {@code Optional}, y la diferencia importa. En un motor en línea
 * hay cuatro desenlaces que operativamente no son lo mismo:
 *
 * <ul>
 *   <li>{@link Disparada} — la regla encontró evidencia.
 *   <li>{@link NoDisparada} — la regla miró y no encontró nada. Es información: significa que la
 *       transacción pasó ese control.
 *   <li>{@link NoAplicable} — la regla no tenía nada que hacer aquí (una regla de huella de
 *       dispositivo en un cajero automático). No es un fallo ni un aprobado.
 *   <li>{@link Degradada} — la regla debía aplicar pero le faltaban datos. <strong>Esto es lo
 *       que se confunde con "no disparada" y produce agujeros silenciosos:</strong> si la caché
 *       de contadores está caída, todas las reglas de ráfaga devuelven "nada" y el motor parece
 *       sano mientras deja pasar el ataque. Distinguirlo permite alertar.
 *   <li>{@link Fallida} — la regla lanzó. El motor la aísla para que no hunda la evaluación.
 * </ul>
 *
 * Al ser una jerarquía sellada, el agregador del motor la recorre con un {@code switch}
 * exhaustivo: añadir un desenlace nuevo rompe la compilación en el punto que debe tratarlo.
 */
public sealed interface ResultadoDeRegla {

    /** La regla encontró evidencia y aporta riesgo. */
    record Disparada(SenalDeRiesgo senal) implements ResultadoDeRegla {
        public Disparada {
            Invariantes.noNulo(senal, "resultado.senal");
        }
    }

    /** La regla se aplicó y no encontró nada. */
    record NoDisparada() implements ResultadoDeRegla {}

    /** La regla no era pertinente para esta transacción. */
    record NoAplicable(String motivo) implements ResultadoDeRegla {
        public NoAplicable {
            motivo = Invariantes.textoConContenido(motivo, "resultado.motivo", 200);
        }
    }

    /** La regla era pertinente pero no pudo decidir por falta de datos. */
    record Degradada(String motivo) implements ResultadoDeRegla {
        public Degradada {
            motivo = Invariantes.textoConContenido(motivo, "resultado.motivo", 200);
        }
    }

    /** La regla lanzó una excepción. El motor la convierte en esto y sigue. */
    record Fallida(String motivo) implements ResultadoDeRegla {
        public Fallida {
            motivo = Invariantes.textoConContenido(motivo, "resultado.motivo", 500);
        }
    }

    /** Instancia única: no tiene estado y se devuelve en la mayoría de las evaluaciones. */
    ResultadoDeRegla NO_DISPARADA = new NoDisparada();

    static ResultadoDeRegla disparada(String codigo, String descripcion, int aporte) {
        return new Disparada(SenalDeRiesgo.de(codigo, descripcion, aporte));
    }

    static ResultadoDeRegla noDisparada() {
        return NO_DISPARADA;
    }

    static ResultadoDeRegla noAplicable(String motivo) {
        return new NoAplicable(motivo);
    }

    static ResultadoDeRegla degradada(String motivo) {
        return new Degradada(motivo);
    }

    /** La regla aportó riesgo y por tanto pesa en la decisión. */
    default boolean aportaRiesgo() {
        return this instanceof Disparada;
    }
}
