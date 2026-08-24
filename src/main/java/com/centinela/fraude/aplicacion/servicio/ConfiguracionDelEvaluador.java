package com.centinela.fraude.aplicacion.servicio;

import com.centinela.fraude.dominio.compartido.Invariantes;
import com.centinela.fraude.dominio.regla.PoliticaDeRiesgo;
import java.time.Duration;
import java.util.Currency;

/**
 * Ajustes de ejecución del caso de uso, agrupados para que su constructor siga siendo legible.
 *
 * <p>Es el objeto al que el adaptador mapeará las propiedades {@code centinela.motor.*}. Nótese
 * que sigue sin haber una anotación de Spring aquí: la traducción desde
 * {@code @ConfigurationProperties} es trabajo del adaptador.
 *
 * @param presupuestoDeLatencia tiempo máximo que el caso de uso espera por sus fuentes de datos
 *     antes de decidir con lo que tenga. No es el tiempo total de la evaluación: las reglas se
 *     ejecutan igualmente después
 * @param monedaPorDefecto moneda con la que se inicializa el perfil de un cliente sin historial
 */
public record ConfiguracionDelEvaluador(
        PoliticaDeRiesgo politica, Duration presupuestoDeLatencia, Currency monedaPorDefecto) {

    public ConfiguracionDelEvaluador {
        Invariantes.noNulo(politica, "configuracion.politica");
        Invariantes.noNulo(presupuestoDeLatencia, "configuracion.presupuestoDeLatencia");
        Invariantes.exigir(
                !presupuestoDeLatencia.isNegative() && !presupuestoDeLatencia.isZero(),
                "El presupuesto de latencia debe ser positivo, pero fue %s",
                presupuestoDeLatencia);
        Invariantes.noNulo(monedaPorDefecto, "configuracion.monedaPorDefecto");
    }

    public static ConfiguracionDelEvaluador porDefecto() {
        return new ConfiguracionDelEvaluador(
                PoliticaDeRiesgo.porDefecto(), Duration.ofMillis(150), Currency.getInstance("EUR"));
    }
}
