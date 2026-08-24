package com.centinela.fraude.dominio.modelo.vo;

/**
 * Canal por el que entra la transacción.
 *
 * <p>Determina qué señales tienen sentido evaluar: la geolocalización es fiable en un POS y
 * poco fiable tras una VPN en WEB; la huella de dispositivo existe en MOVIL pero no en una
 * autorización recurrente por API.
 */
public enum Canal {
    /** Comercio electrónico en navegador. */
    WEB(false, true),
    /** Aplicación nativa del cliente. */
    MOVIL(false, true),
    /** Terminal punto de venta con tarjeta presente. */
    POS(true, false),
    /** Cajero automático. */
    CAJERO(true, false),
    /** Integración servidor a servidor (suscripciones, recurrentes, agregadores). */
    API(false, false),
    /** Operación tomada por un agente en un centro de llamadas. */
    TELEFONICO(false, false);

    private final boolean presenciaFisica;
    private final boolean soportaHuellaDeDispositivo;

    Canal(boolean presenciaFisica, boolean soportaHuellaDeDispositivo) {
        this.presenciaFisica = presenciaFisica;
        this.soportaHuellaDeDispositivo = soportaHuellaDeDispositivo;
    }

    /** El titular estaba físicamente presente con el instrumento en la mano. */
    public boolean presenciaFisica() {
        return presenciaFisica;
    }

    /** Card Not Present: el segmento donde se concentra la mayor parte del fraude. */
    public boolean esNoPresencial() {
        return !presenciaFisica;
    }

    public boolean soportaHuellaDeDispositivo() {
        return soportaHuellaDeDispositivo;
    }
}
