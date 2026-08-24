package com.centinela.fraude.dominio.modelo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.centinela.fraude.dominio.ObjetosDePrueba;
import com.centinela.fraude.dominio.excepcion.ViolacionDeInvariante;
import com.centinela.fraude.dominio.modelo.vo.Geolocalizacion;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Geolocalizacion")
class GeolocalizacionTest {

    @Test
    @DisplayName("mide la distancia de círculo máximo entre dos puntos")
    void mideLaDistancia() {
        // Madrid - Tokio son unos 10.775 km en línea recta.
        double distancia = ObjetosDePrueba.madrid().distanciaKmHasta(ObjetosDePrueba.tokio());

        assertThat(distancia).isCloseTo(10_775.0, within(50.0));
    }

    @Test
    @DisplayName("la distancia entre un punto y sí mismo es cero")
    void distanciaCero() {
        assertThat(ObjetosDePrueba.madrid().distanciaKmHasta(ObjetosDePrueba.madrid())).isZero();
    }

    @Test
    @DisplayName("detecta viaje imposible cuando la velocidad implícita no la alcanza ningún avión")
    void detectaViajeImposible() {
        boolean imposible = ObjetosDePrueba.tokio()
                .implicaViajeImposibleDesde(ObjetosDePrueba.madrid(), Duration.ofHours(2));

        assertThat(imposible).isTrue();
    }

    @Test
    @DisplayName("no acusa cuando el desplazamiento cabe en el tiempo transcurrido")
    void noAcusaViajePosible() {
        boolean imposible = ObjetosDePrueba.tokio()
                .implicaViajeImposibleDesde(ObjetosDePrueba.madrid(), Duration.ofHours(24));

        assertThat(imposible).isFalse();
    }

    @Test
    @DisplayName("dos posiciones distintas en el mismo instante son siempre imposibles")
    void intervaloCeroConDesplazamiento() {
        assertThat(ObjetosDePrueba.tokio().velocidadImplicitaKmH(ObjetosDePrueba.madrid(), Duration.ZERO))
                .isInfinite();
    }

    @Test
    @DisplayName("rechaza coordenadas fuera del rango geográfico")
    void rechazaCoordenadasInvalidas() {
        assertThatThrownBy(() -> Geolocalizacion.de(91.0, 0.0, "ES")).isInstanceOf(ViolacionDeInvariante.class);
        assertThatThrownBy(() -> Geolocalizacion.de(0.0, 181.0, "ES")).isInstanceOf(ViolacionDeInvariante.class);
    }

    @Test
    @DisplayName("normaliza el código de país a mayúsculas")
    void normalizaElPais() {
        assertThat(Geolocalizacion.de(40.4168, -3.7038, "es").paisIso2()).isEqualTo("ES");
    }

    @Test
    @DisplayName("rechaza códigos de país que no son ISO-3166-1 alfa-2")
    void rechazaPaisInvalido() {
        assertThatThrownBy(() -> Geolocalizacion.de(0.0, 0.0, "ESP")).isInstanceOf(ViolacionDeInvariante.class);
    }
}
