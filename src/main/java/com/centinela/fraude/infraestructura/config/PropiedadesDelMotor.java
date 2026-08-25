package com.centinela.fraude.infraestructura.config;

import com.centinela.fraude.aplicacion.servicio.ConfiguracionDelEvaluador;
import com.centinela.fraude.dominio.regla.PoliticaDeRiesgo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Currency;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Propiedades {@code centinela.motor}: presupuesto de latencia y política de riesgo.
 *
 * <p>Este es el punto donde la calibración deja de ser código. Los pesos de las reglas y los
 * umbrales de ráfaga viven aquí porque se recalibran cada pocas semanas mirando la tasa de
 * falsos positivos, y una recalibración no puede exigir un despliegue.
 *
 * <p>La traducción a {@link PoliticaDeRiesgo} ocurre en esta clase, no en el dominio: el núcleo
 * no sabe que existe un YAML.
 */
@Validated
@ConfigurationProperties("centinela.motor")
public record PropiedadesDelMotor(
        @NotNull Duration presupuestoLatencia,
        @NotBlank String monedaPorDefecto,
        @NotNull @Valid Politica politica,
        @NotNull @Valid Bandeja bandeja) {

    /**
     * Ajustes del relevo del outbox.
     *
     * @param tamanoDeLote cuántas decisiones publica un ciclo; acota lo que puede tardar
     * @param intentosAntesDeAlertar a partir de aquí, un fallo de publicación deja de ser ruido
     *     y pasa a ser un incidente: alguien aguas abajo lleva ese tiempo sin enterarse
     */
    public record Bandeja(
            @Positive int tamanoDeLote,
            @Positive int intentosAntesDeAlertar,
            @NotNull Duration intervaloDeRelevo,
            @NotNull Duration retrasoInicial) {}

    /**
     * @param aportes peso por código de regla; lo ausente usa el valor por defecto de la regla
     */
    public record Politica(
            @NotBlank String version,
            @NotNull Duration ventanaVelocidad,
            @Positive long maxTransaccionesPorClienteEnVentana,
            @Positive long maxIntentosPorDispositivoEnVentana,
            @NotNull Duration toleranciaDesfaseReloj,
            @NotNull BigDecimal factorDesviacionModerada,
            @NotNull BigDecimal factorDesviacionAlta,
            @NotNull BigDecimal factorDesviacionExtrema,
            Map<String, Integer> aportes) {

        public PoliticaDeRiesgo aDominio() {
            return new PoliticaDeRiesgo(
                    version,
                    ventanaVelocidad,
                    maxTransaccionesPorClienteEnVentana,
                    maxIntentosPorDispositivoEnVentana,
                    toleranciaDesfaseReloj,
                    factorDesviacionModerada,
                    factorDesviacionAlta,
                    factorDesviacionExtrema,
                    aportes == null ? Map.of() : aportes);
        }
    }

    public ConfiguracionDelEvaluador aConfiguracionDelEvaluador() {
        return new ConfiguracionDelEvaluador(
                politica.aDominio(), presupuestoLatencia, Currency.getInstance(monedaPorDefecto));
    }
}
