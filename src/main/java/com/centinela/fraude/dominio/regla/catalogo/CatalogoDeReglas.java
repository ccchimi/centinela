package com.centinela.fraude.dominio.regla.catalogo;

import com.centinela.fraude.dominio.regla.ReglaDeFraude;
import java.util.List;

/**
 * Catálogo estándar de reglas, en el orden en que se ejecutan.
 *
 * <p>El orden no afecta al puntaje —los aportes se suman y la suma es conmutativa— pero sí al
 * orden en que aparece la evidencia antes de ordenarla por peso, y a la lectura del código. Se
 * declaran de más específica a más contextual.
 *
 * <p>Vive en el dominio, no en la configuración de Spring: qué reglas componen el motor es una
 * decisión de negocio, no de cableado. El adaptador se limitará a llamar a
 * {@link #estandar()} y a envolverlo en un {@code MotorDeReglas}.
 */
public final class CatalogoDeReglas {

    private CatalogoDeReglas() {
        throw new AssertionError("Clase de utilidades, no instanciable");
    }

    /** Las nueve reglas de la primera versión del motor. */
    public static List<ReglaDeFraude> estandar() {
        return List.of(
                new ReglaMarcaTemporalIncoherente(),
                new ReglaViajeImposible(),
                new ReglaRafagaPorDispositivo(),
                new ReglaRafagaPorCliente(),
                new ReglaDesviacionDeImporte(),
                new ReglaDispositivoDesconocido(),
                new ReglaPaisInusual(),
                new ReglaInstrumentoDeRiesgo(),
                new ReglaComercioDeAltoRiesgo());
    }
}
