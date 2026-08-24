package com.centinela.fraude.dominio.regla.catalogo;

import static com.centinela.fraude.dominio.regla.Resultados.noAplico;
import static com.centinela.fraude.dominio.regla.Resultados.noSeDisparo;
import static com.centinela.fraude.dominio.regla.Resultados.seDegrado;
import static com.centinela.fraude.dominio.regla.Resultados.seDisparo;
import static org.assertj.core.api.Assertions.assertThat;

import com.centinela.fraude.dominio.ObjetosDePrueba;
import com.centinela.fraude.dominio.PerfilDePrueba;
import com.centinela.fraude.dominio.modelo.SenalDeRiesgo;
import com.centinela.fraude.dominio.modelo.Transaccion;
import com.centinela.fraude.dominio.modelo.vo.Canal;
import com.centinela.fraude.dominio.modelo.vo.Comercio;
import com.centinela.fraude.dominio.modelo.vo.Geolocalizacion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Reglas de contexto")
class ReglasDeContextoTest {

    @Nested
    @DisplayName("ReglaPaisInusual")
    class PaisInusual {

        private final ReglaPaisInusual regla = new ReglaPaisInusual();

        @Test
        @DisplayName("no dispara desde un país habitual")
        void noDisparaDesdePaisHabitual() {
            noSeDisparo(regla.evaluar(ObjetosDePrueba.contexto(ObjetosDePrueba.transaccionWeb())));
        }

        @Test
        @DisplayName("dispara desde un país nuevo, con extra si además cruza frontera")
        void disparaDesdePaisNuevo() {
            // Origen JP, comercio ES: además de país nuevo, la operación cruza frontera.
            Transaccion desdeTokio = ObjetosDePrueba.transaccionDesde(ObjetosDePrueba.tokio());

            SenalDeRiesgo senal = seDisparo(regla.evaluar(ObjetosDePrueba.contexto(desdeTokio)));

            assertThat(senal.aporte()).isEqualTo(180); // 120 de base + 60 por cruce
            assertThat(senal.descripcion()).contains("JP").contains("ES");
        }

        @Test
        @DisplayName("sin cruce de frontera aplica solo el aporte base")
        void sinCruceDeFronteraSoloLaBase() {
            Comercio comercioJapones = Comercio.de("com-jp", "Tienda de Tokio", "5942", "JP");
            Transaccion enJapon = ObjetosDePrueba.transaccion(
                    ObjetosDePrueba.euros("120.00"),
                    ObjetosDePrueba.tarjetaNoPresente(),
                    comercioJapones,
                    Canal.WEB,
                    ObjetosDePrueba.tokio(),
                    ObjetosDePrueba.DISPOSITIVO_CONOCIDO);

            assertThat(seDisparo(regla.evaluar(ObjetosDePrueba.contexto(enJapon))).aporte())
                    .isEqualTo(120);
        }

        @Test
        @DisplayName("no aplica sin geolocalización")
        void noAplicaSinGeolocalizacion() {
            noAplico(regla.evaluar(ObjetosDePrueba.contexto(ObjetosDePrueba.transaccionEn(Canal.API))));
        }

        @Test
        @DisplayName("se degrada con un cliente sin países habituales conocidos")
        void seDegradaConClienteNuevo() {
            var perfil = PerfilDePrueba.unPerfil().sinHistorialSuficiente().construir();

            assertThat(seDegrado(regla.evaluar(ObjetosDePrueba.contexto(
                            ObjetosDePrueba.transaccionDesde(ObjetosDePrueba.tokio()), perfil))))
                    .contains("países habituales");
        }
    }

    @Nested
    @DisplayName("ReglaComercioDeAltoRiesgo")
    class ComercioDeAltoRiesgo {

        private final ReglaComercioDeAltoRiesgo regla = new ReglaComercioDeAltoRiesgo();

        private Transaccion enCasaDeApuestas() {
            return ObjetosDePrueba.transaccion(
                    ObjetosDePrueba.euros("120.00"),
                    ObjetosDePrueba.tarjetaNoPresente(),
                    ObjetosDePrueba.comercioDeAltoRiesgo(),
                    Canal.WEB,
                    ObjetosDePrueba.madrid(),
                    ObjetosDePrueba.DISPOSITIVO_CONOCIDO);
        }

        @Test
        @DisplayName("dispara con un MCC de categoría sensible")
        void disparaConMccSensible() {
            SenalDeRiesgo senal = seDisparo(regla.evaluar(ObjetosDePrueba.contexto(enCasaDeApuestas())));

            assertThat(senal.aporte()).isEqualTo(90);
            assertThat(senal.descripcion()).contains("7995");
        }

        @Test
        @DisplayName("no dispara con un comercio corriente")
        void noDisparaConComercioCorriente() {
            noSeDisparo(regla.evaluar(ObjetosDePrueba.contexto(ObjetosDePrueba.transaccionWeb())));
        }

        @Test
        @DisplayName("se atenúa si el titular ya opera habitualmente con ese comercio")
        void noDisparaSiEsComercioHabitual() {
            // El jugador recurrente no es sospechoso por serlo.
            var perfil = PerfilDePrueba.unPerfil().conComercios("com-999").construir();

            noSeDisparo(regla.evaluar(ObjetosDePrueba.contexto(enCasaDeApuestas(), perfil)));
        }
    }

    @Nested
    @DisplayName("ReglaMarcaTemporalIncoherente")
    class MarcaTemporal {

        private final ReglaMarcaTemporalIncoherente regla = new ReglaMarcaTemporalIncoherente();

        @Test
        @DisplayName("no dispara con una marca temporal del pasado")
        void noDisparaConMarcaPasada() {
            noSeDisparo(regla.evaluar(ObjetosDePrueba.contexto(
                    ObjetosDePrueba.transaccionOcurridaEn(ObjetosDePrueba.AHORA.minusSeconds(30)))));
        }

        @Test
        @DisplayName("tolera un desfase de reloj dentro del margen")
        void toleraDesfaseDentroDelMargen() {
            // Tolerancia por defecto: 5 minutos.
            noSeDisparo(regla.evaluar(ObjetosDePrueba.contexto(
                    ObjetosDePrueba.transaccionOcurridaEn(ObjetosDePrueba.AHORA.plusSeconds(120)))));
        }

        @Test
        @DisplayName("dispara cuando la transacción dice venir del futuro lejano")
        void disparaConMarcaFutura() {
            SenalDeRiesgo senal = seDisparo(regla.evaluar(ObjetosDePrueba.contexto(
                    ObjetosDePrueba.transaccionOcurridaEn(ObjetosDePrueba.AHORA.plusSeconds(3600)))));

            assertThat(senal.aporte()).isEqualTo(150);
            assertThat(senal.descripcion()).contains("futuro");
        }
    }

    @Test
    @DisplayName("una geolocalización con país en minúsculas se compara igual")
    void elPaisSeNormaliza() {
        var perfil = PerfilDePrueba.unPerfil().conPaises("es").construir();
        Transaccion enMadrid = ObjetosDePrueba.transaccionDesde(Geolocalizacion.de(40.4168, -3.7038, "es"));

        noSeDisparo(new ReglaPaisInusual().evaluar(ObjetosDePrueba.contexto(enMadrid, perfil)));
    }
}
