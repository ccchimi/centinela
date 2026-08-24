package com.centinela.fraude.dominio.modelo;

/**
 * Veredicto que el motor devuelve al autorizador.
 *
 * <p>Cuatro valores y no dos: en fraude, forzar la decisión a "aprobar o rechazar" maximiza uno
 * de los dos errores. {@link #DESAFIAR} traslada la duda al titular (autenticación reforzada) y
 * {@link #REVISAR} la traslada a un analista, y ambos son respuestas legítimas para la franja
 * intermedia donde vive la mayor parte del volumen dudoso.
 */
public enum Decision {

    /** Se deja pasar sin fricción. */
    APROBAR(false, false),

    /** Se exige autenticación reforzada (3-D Secure, biometría) antes de continuar. */
    DESAFIAR(false, true),

    /** Se aprueba pero se encola para revisión manual posterior. */
    REVISAR(false, false),

    /** Se deniega la autorización. */
    RECHAZAR(true, false);

    private final boolean bloquea;
    private final boolean exigeAutenticacion;

    Decision(boolean bloquea, boolean exigeAutenticacion) {
        this.bloquea = bloquea;
        this.exigeAutenticacion = exigeAutenticacion;
    }

    /** La transacción no se completa. */
    public boolean bloquea() {
        return bloquea;
    }

    /** La transacción continúa solo si el titular supera un reto adicional. */
    public boolean exigeAutenticacion() {
        return exigeAutenticacion;
    }

    /** Introduce fricción perceptible para el cliente; es la métrica a minimizar sin perder cobertura. */
    public boolean introduceFriccion() {
        return bloquea || exigeAutenticacion;
    }
}
