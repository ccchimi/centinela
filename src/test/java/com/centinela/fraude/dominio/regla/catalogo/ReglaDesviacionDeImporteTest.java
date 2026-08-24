package com.centinela.fraude.dominio.regla.catalogo;

import static com.centinela.fraude.dominio.regla.Resultados.noSeDisparo;
import static com.centinela.fraude.dominio.regla.Resultados.seDegrado;
import static com.centinela.fraude.dominio.regla.Resultados.seDisparo;
import static org.assertj.core.api.Assertions.assertThat;

import com.centinela.fraude.dominio.ObjetosDePrueba;
import com.centinela.fraude.dominio.PerfilDePrueba;
import com.centinela.fraude.dominio.modelo.PerfilDeComportamiento;
import com.centinela.fraude.dominio.modelo.SenalDeRiesgo;
import com.centinela.fraude.dominio.modelo.vo.Dinero;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("ReglaDesviacionDeImporte")
class ReglaDesviacionDeImporteTest {

    private final ReglaDesviacionDeImporte regla = new ReglaDesviacionDeImporte();

    /** Perfil fiable con media de 100 EUR: los factores de la política caen en 500/1000/2000. */
    private static PerfilDeComportamiento perfilConMediaDeCien() {
        return PerfilDePrueba.unPerfil().conMedia("100.00").construir();
    }

    @ParameterizedTest(name = "{0} EUR sobre una media de 100 aporta {1}")
    @CsvSource({
        "499.00, 0", // por debajo de x5: no dispara
        "500.00, 120", // x5  -> moderada
        "999.00, 120",
        "1000.00, 220", // x10 -> alta
        "1999.00, 220",
        "2000.00, 340", // x20 -> extrema
        "9000.00, 340"
    })
    @DisplayName("escalona el aporte en tres tramos según la desviación")
    void escalonaElAporte(String importe, int aporteEsperado) {
        var contexto = ObjetosDePrueba.contexto(
                ObjetosDePrueba.transaccionDe(ObjetosDePrueba.euros(importe)), perfilConMediaDeCien());

        if (aporteEsperado == 0) {
            noSeDisparo(regla.evaluar(contexto));
        } else {
            assertThat(seDisparo(regla.evaluar(contexto)).aporte()).isEqualTo(aporteEsperado);
        }
    }

    @Test
    @DisplayName("el texto de la señal explica el tramo y la razón, para el analista")
    void laSenalEsExplicable() {
        var contexto = ObjetosDePrueba.contexto(
                ObjetosDePrueba.transaccionDe(ObjetosDePrueba.euros("2500.00")), perfilConMediaDeCien());

        SenalDeRiesgo senal = seDisparo(regla.evaluar(contexto));

        assertThat(senal.descripcion()).contains("extrema").contains("2500.00 EUR").contains("100.00 EUR");
    }

    @Test
    @DisplayName("se degrada con un cliente nuevo en vez de castigarlo")
    void seDegradaConClienteNuevo() {
        // El error más caro de un motor de fraude: tratar la falta de historial como anomalía.
        var perfil = PerfilDePrueba.unPerfil().sinHistorialSuficiente().construir();
        var contexto =
                ObjetosDePrueba.contexto(ObjetosDePrueba.transaccionDe(ObjetosDePrueba.euros("5000.00")), perfil);

        assertThat(seDegrado(regla.evaluar(contexto))).contains("insuficientes");
    }

    @Test
    @DisplayName("se degrada si el perfil no tiene media calculada")
    void seDegradaSinMedia() {
        var perfil = PerfilDePrueba.unPerfil().sinMedia().construir();
        var contexto =
                ObjetosDePrueba.contexto(ObjetosDePrueba.transaccionDe(ObjetosDePrueba.euros("5000.00")), perfil);

        assertThat(seDegrado(regla.evaluar(contexto))).contains("importe medio");
    }

    @Test
    @DisplayName("se degrada ante monedas distintas en vez de dejar que el dominio lance")
    void seDegradaConMonedasDistintas() {
        // Dinero.razonRespectoA lanza si las monedas no coinciden. La regla lo previene: una
        // excepción aquí acabaría como advertencia genérica y se perdería el motivo real.
        var perfil = PerfilDePrueba.unPerfil()
                .conMedia(Dinero.de("100.00", "USD"))
                .construir();
        var contexto =
                ObjetosDePrueba.contexto(ObjetosDePrueba.transaccionDe(ObjetosDePrueba.euros("5000.00")), perfil);

        assertThat(seDegrado(regla.evaluar(contexto))).contains("EUR").contains("USD");
    }

    @Test
    @DisplayName("no dispara con un importe dentro de lo habitual")
    void noDisparaConImporteNormal() {
        noSeDisparo(regla.evaluar(ObjetosDePrueba.contexto(ObjetosDePrueba.transaccionWeb(), perfilConMediaDeCien())));
    }
}
