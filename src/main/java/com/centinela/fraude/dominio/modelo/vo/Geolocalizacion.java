package com.centinela.fraude.dominio.modelo.vo;

import com.centinela.fraude.dominio.compartido.Invariantes;
import java.time.Duration;

/**
 * Punto geográfico desde el que se origina la transacción.
 *
 * <p>Contiene la lógica de <em>viaje imposible</em>, una de las señales clásicas del dominio:
 * dos operaciones del mismo titular separadas por una distancia que ningún medio de transporte
 * cubre en el tiempo transcurrido implican que al menos una de las dos no la hizo el titular.
 */
public record Geolocalizacion(double latitud, double longitud, String paisIso2) {

    /** Radio medio de la Tierra, en kilómetros (esfera WGS-84 aproximada). */
    private static final double RADIO_TERRESTRE_KM = 6371.0088;

    /**
     * Umbral de velocidad implícita. Por encima, ningún desplazamiento físico lo explica: un
     * vuelo comercial de largo radio cruza a ~900 km/h y el margen cubre la imprecisión de la
     * geolocalización por IP.
     */
    private static final double VELOCIDAD_IMPOSIBLE_KMH = 1000.0;

    public Geolocalizacion {
        Invariantes.enRango(latitud, -90.0, 90.0, "geolocalizacion.latitud");
        Invariantes.enRango(longitud, -180.0, 180.0, "geolocalizacion.longitud");
        paisIso2 = Invariantes.paisIso2(paisIso2, "geolocalizacion.paisIso2");
    }

    public static Geolocalizacion de(double latitud, double longitud, String paisIso2) {
        return new Geolocalizacion(latitud, longitud, paisIso2);
    }

    /** Distancia de círculo máximo (fórmula de Haversine) en kilómetros. */
    public double distanciaKmHasta(Geolocalizacion otra) {
        Invariantes.noNulo(otra, "otra geolocalización");

        double latitudOrigenRad = Math.toRadians(latitud);
        double latitudDestinoRad = Math.toRadians(otra.latitud);
        double deltaLatitud = Math.toRadians(otra.latitud - latitud);
        double deltaLongitud = Math.toRadians(otra.longitud - longitud);

        double a = Math.pow(Math.sin(deltaLatitud / 2), 2)
                + Math.cos(latitudOrigenRad) * Math.cos(latitudDestinoRad) * Math.pow(Math.sin(deltaLongitud / 2), 2);

        return 2 * RADIO_TERRESTRE_KM * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    /**
     * Velocidad media que habría hecho falta para cubrir la distancia en el tiempo dado.
     *
     * @return {@link Double#POSITIVE_INFINITY} si el intervalo es nulo o negativo y las
     *     posiciones difieren; {@code 0} si no hubo desplazamiento
     */
    public double velocidadImplicitaKmH(Geolocalizacion otra, Duration transcurrido) {
        Invariantes.noNulo(transcurrido, "intervalo transcurrido");
        double distanciaKm = distanciaKmHasta(otra);
        if (distanciaKm == 0.0) {
            return 0.0;
        }
        double horas = transcurrido.toNanos() / (double) Duration.ofHours(1).toNanos();
        return horas <= 0 ? Double.POSITIVE_INFINITY : distanciaKm / horas;
    }

    /** Señal de viaje imposible entre esta posición y la anterior del mismo titular. */
    public boolean implicaViajeImposibleDesde(Geolocalizacion anterior, Duration transcurrido) {
        return velocidadImplicitaKmH(anterior, transcurrido) > VELOCIDAD_IMPOSIBLE_KMH;
    }

    public boolean mismoPaisQue(String codigoPaisIso2) {
        return paisIso2.equalsIgnoreCase(codigoPaisIso2);
    }

    @Override
    public String toString() {
        return "%s (%.4f, %.4f)".formatted(paisIso2, latitud, longitud);
    }
}
