package com.centinela.fraude.dominio.regla;

import com.centinela.fraude.dominio.compartido.Invariantes;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

/**
 * Parámetros calibrables del motor: umbrales, ventanas y el peso de cada regla.
 *
 * <p>Existe porque estos números no son conocimiento del dominio, son una palanca de negocio.
 * Se recalibran cada pocas semanas mirando la tasa de falsos positivos y no pueden exigir un
 * despliegue cada vez. Al ser un value object inmutable, además, una evaluación puede declarar
 * bajo qué {@link #version()} se tomó, que es lo primero que pregunta cualquiera al revisar una
 * decisión de hace tres meses.
 *
 * <p>Lo que <em>no</em> está aquí, todavía: las bandas de {@code NivelDeRiesgo}. Siguen siendo
 * constantes del enum y se moverán cuando exista el adaptador de configuración; mezclar las dos
 * cosas ahora obligaría a arrastrar la política hasta el constructor de
 * {@code EvaluacionDeFraude} sin ganar nada en esta iteración.
 *
 * @param aportes peso por código de regla; una regla ausente del mapa usa su valor por defecto
 */
public record PoliticaDeRiesgo(
        String version,
        Duration ventanaDeVelocidad,
        long maxTransaccionesPorClienteEnVentana,
        long maxIntentosPorDispositivoEnVentana,
        Duration toleranciaDeDesfaseDeReloj,
        BigDecimal factorDesviacionModerada,
        BigDecimal factorDesviacionAlta,
        BigDecimal factorDesviacionExtrema,
        Map<String, Integer> aportes) {

    public PoliticaDeRiesgo {
        version = Invariantes.textoConContenido(version, "politica.version", 32);
        Invariantes.noNulo(ventanaDeVelocidad, "politica.ventanaDeVelocidad");
        Invariantes.exigir(
                !ventanaDeVelocidad.isNegative() && !ventanaDeVelocidad.isZero(),
                "La ventana de velocidad debe ser positiva, pero fue %s",
                ventanaDeVelocidad);
        Invariantes.noNulo(toleranciaDeDesfaseDeReloj, "politica.toleranciaDeDesfaseDeReloj");
        Invariantes.exigir(
                !toleranciaDeDesfaseDeReloj.isNegative(),
                "La tolerancia de desfase de reloj no puede ser negativa, pero fue %s",
                toleranciaDeDesfaseDeReloj);
        Invariantes.exigir(
                maxTransaccionesPorClienteEnVentana > 0, "politica.maxTransaccionesPorClienteEnVentana debe ser positivo");
        Invariantes.exigir(
                maxIntentosPorDispositivoEnVentana > 0, "politica.maxIntentosPorDispositivoEnVentana debe ser positivo");

        Invariantes.noNulo(factorDesviacionModerada, "politica.factorDesviacionModerada");
        Invariantes.noNulo(factorDesviacionAlta, "politica.factorDesviacionAlta");
        Invariantes.noNulo(factorDesviacionExtrema, "politica.factorDesviacionExtrema");
        Invariantes.exigir(
                factorDesviacionModerada.compareTo(factorDesviacionAlta) < 0
                        && factorDesviacionAlta.compareTo(factorDesviacionExtrema) < 0,
                "Los factores de desviación deben ser estrictamente crecientes: %s < %s < %s",
                factorDesviacionModerada,
                factorDesviacionAlta,
                factorDesviacionExtrema);

        aportes = Map.copyOf(Invariantes.noNulo(aportes, "politica.aportes"));
    }

    /**
     * Calibración inicial. Los pesos salen de repartir la escala [0, 1000] de forma que ninguna
     * señal aislada baste para rechazar: hacen falta al menos dos indicios fuertes para llegar a
     * la banda crítica. Es una decisión deliberada contra los falsos positivos.
     */
    public static PoliticaDeRiesgo porDefecto() {
        return new PoliticaDeRiesgo(
                "v1-inicial",
                Duration.ofSeconds(60),
                8,
                6,
                Duration.ofMinutes(5),
                new BigDecimal("5"),
                new BigDecimal("10"),
                new BigDecimal("20"),
                Map.of());
    }

    /** Peso configurado para una regla, o su valor por defecto si la política no lo fija. */
    public int aporteDe(String codigoDeRegla, int porDefecto) {
        Invariantes.textoConContenido(codigoDeRegla, "codigoDeRegla");
        return aportes.getOrDefault(codigoDeRegla, porDefecto);
    }

    /** Copia de esta política con pesos distintos, para pruebas y para despliegues progresivos. */
    public PoliticaDeRiesgo conAportes(Map<String, Integer> nuevosAportes) {
        return new PoliticaDeRiesgo(
                version,
                ventanaDeVelocidad,
                maxTransaccionesPorClienteEnVentana,
                maxIntentosPorDispositivoEnVentana,
                toleranciaDeDesfaseDeReloj,
                factorDesviacionModerada,
                factorDesviacionAlta,
                factorDesviacionExtrema,
                nuevosAportes);
    }
}
