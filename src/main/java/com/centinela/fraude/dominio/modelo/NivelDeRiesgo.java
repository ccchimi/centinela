package com.centinela.fraude.dominio.modelo;

import com.centinela.fraude.dominio.modelo.vo.PuntajeDeRiesgo;

/**
 * Banda de riesgo derivada del puntaje. Es la traducción del número continuo al lenguaje que
 * usan negocio, cumplimiento y los cuadros de mando.
 *
 * <p><strong>Provisional:</strong> los umbrales están aquí como constantes para que el dominio
 * sea autocontenido en esta primera iteración. Cuando entre el motor de reglas pasarán a un
 * {@code PoliticaDeRiesgo} inyectado como value object, porque son una palanca de negocio que
 * se recalibra cada pocas semanas y no puede exigir un despliegue.
 */
public enum NivelDeRiesgo {

    BAJO(0, 249, Decision.APROBAR),
    MEDIO(250, 599, Decision.DESAFIAR),
    ALTO(600, 849, Decision.REVISAR),
    CRITICO(850, PuntajeDeRiesgo.MAXIMO, Decision.RECHAZAR);

    private final int umbralInferior;
    private final int umbralSuperior;
    private final Decision decisionPorDefecto;

    NivelDeRiesgo(int umbralInferior, int umbralSuperior, Decision decisionPorDefecto) {
        this.umbralInferior = umbralInferior;
        this.umbralSuperior = umbralSuperior;
        this.decisionPorDefecto = decisionPorDefecto;
    }

    /**
     * Clasifica un puntaje. Recorre las bandas en orden de declaración, que cubren [0, 1000] sin
     * huecos ni solapes; el fallo final es inalcanzable y está solo para que el compilador vea
     * un retorno en todas las rutas.
     */
    public static NivelDeRiesgo desde(PuntajeDeRiesgo puntaje) {
        int valor = puntaje.valor();
        for (NivelDeRiesgo nivel : values()) {
            if (valor >= nivel.umbralInferior && valor <= nivel.umbralSuperior) {
                return nivel;
            }
        }
        throw new IllegalStateException("Puntaje fuera de todas las bandas: " + valor);
    }

    public int umbralInferior() {
        return umbralInferior;
    }

    public int umbralSuperior() {
        return umbralSuperior;
    }

    /** Decisión que corresponde a esta banda salvo que una regla la endurezca. */
    public Decision decisionPorDefecto() {
        return decisionPorDefecto;
    }

    public boolean esAlMenos(NivelDeRiesgo otro) {
        return ordinal() >= otro.ordinal();
    }
}
