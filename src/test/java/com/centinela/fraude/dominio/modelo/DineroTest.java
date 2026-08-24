package com.centinela.fraude.dominio.modelo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.centinela.fraude.dominio.excepcion.ReglaDeNegocioIncumplida;
import com.centinela.fraude.dominio.excepcion.ViolacionDeInvariante;
import com.centinela.fraude.dominio.modelo.vo.Dinero;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Dinero")
class DineroTest {

    private static final Currency EUR = Currency.getInstance("EUR");

    @Nested
    @DisplayName("igualdad")
    class Igualdad {

        @Test
        @DisplayName("normaliza la escala para que 10.0 y 10.00 sean el mismo importe")
        void normalizaLaEscala() {
            Dinero a = Dinero.de("10.0", "EUR");
            Dinero b = Dinero.de("10.00", "EUR");

            assertThat(a).isEqualTo(b);
            assertThat(a).hasSameHashCodeAs(b);
            // La comprobación que de verdad importa: sin normalizar el Set tendría 2 elementos,
            // y usar 10.0 como clave no encontraría lo guardado bajo 10.00.
            assertThat(Set.copyOf(List.of(a, b))).hasSize(1);
        }

        @Test
        @DisplayName("distingue importes iguales en monedas distintas")
        void distingueMonedas() {
            assertThat(Dinero.de("10.00", "EUR")).isNotEqualTo(Dinero.de("10.00", "USD"));
        }

        @Test
        @DisplayName("redondea a los decimales de la moneda con redondeo bancario")
        void redondeaAlaMoneda() {
            assertThat(Dinero.de("10.005", "EUR").importe()).isEqualByComparingTo("10.00");
            assertThat(Dinero.de("10.015", "EUR").importe()).isEqualByComparingTo("10.02");
            // El yen no tiene decimales.
            assertThat(Dinero.de("1250.6", "JPY").importe()).isEqualByComparingTo("1251");
        }
    }

    @Nested
    @DisplayName("aritmética")
    class Aritmetica {

        @Test
        @DisplayName("suma y resta dentro de la misma moneda")
        void sumaYResta() {
            assertThat(Dinero.de("10.00", "EUR").mas(Dinero.de("5.50", "EUR"))).isEqualTo(Dinero.de("15.50", "EUR"));
            assertThat(Dinero.de("10.00", "EUR").menos(Dinero.de("5.50", "EUR"))).isEqualTo(Dinero.de("4.50", "EUR"));
        }

        @Test
        @DisplayName("rechaza operar monedas distintas en vez de inventar una conversión")
        void rechazaMonedasDistintas() {
            Dinero euros = Dinero.de("10.00", "EUR");
            Dinero dolares = Dinero.de("10.00", "USD");

            assertThatThrownBy(() -> euros.mas(dolares))
                    .isInstanceOf(ReglaDeNegocioIncumplida.class)
                    .hasMessageContaining("EUR")
                    .hasMessageContaining("USD");
        }

        @Test
        @DisplayName("calcula la razón sobre una base, y devuelve cero si la base es cero")
        void calculaLaRazon() {
            assertThat(Dinero.de("300.00", "EUR").razonRespectoA(Dinero.de("100.00", "EUR")))
                    .isEqualByComparingTo("3.0000");
            assertThat(Dinero.de("300.00", "EUR").razonRespectoA(Dinero.cero(EUR)))
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("construye desde la unidad mínima que viaja en el mensaje de autorización")
        void construyeDesdeUnidadMinima() {
            assertThat(Dinero.deUnidadMinima(12_050L, EUR)).isEqualTo(Dinero.de("120.50", "EUR"));
        }
    }

    @Nested
    @DisplayName("invariantes")
    class Invariantes {

        @Test
        @DisplayName("no admite importes negativos")
        void rechazaNegativos() {
            assertThatThrownBy(() -> Dinero.de("-1.00", "EUR")).isInstanceOf(ViolacionDeInvariante.class);
        }

        @Test
        @DisplayName("no admite moneda ausente")
        void rechazaMonedaNula() {
            assertThatThrownBy(() -> new Dinero(BigDecimal.TEN, null)).isInstanceOf(ViolacionDeInvariante.class);
        }
    }
}
