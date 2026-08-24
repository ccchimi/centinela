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
import com.centinela.fraude.dominio.regla.PoliticaDeRiesgo;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ReglaViajeImposible")
class ReglaViajeImposibleTest {

    private final ReglaViajeImposible regla = new ReglaViajeImposible();

    @Test
    @DisplayName("dispara cuando el titular no pudo desplazarse en el tiempo transcurrido")
    void disparaAnteUnDesplazamientoImposible() {
        // Visto en Madrid hace una hora, opera ahora desde Tokio.
        Transaccion desdeTokio = ObjetosDePrueba.transaccionDesde(ObjetosDePrueba.tokio());
        var perfil = PerfilDePrueba.unPerfil()
                .vistoEn(ObjetosDePrueba.madrid(), ObjetosDePrueba.AHORA.minusSeconds(3600))
                .construir();

        SenalDeRiesgo senal = seDisparo(regla.evaluar(ObjetosDePrueba.contexto(desdeTokio, perfil)));

        assertThat(senal.codigo()).isEqualTo(ReglaViajeImposible.CODIGO);
        assertThat(senal.aporte()).isEqualTo(400);
        assertThat(senal.descripcion()).contains("km");
    }

    @Test
    @DisplayName("no dispara si el desplazamiento cabe en el tiempo transcurrido")
    void noDisparaSiElViajeEsPosible() {
        Transaccion desdeTokio = ObjetosDePrueba.transaccionDesde(ObjetosDePrueba.tokio());
        var perfil = PerfilDePrueba.unPerfil()
                .vistoEn(ObjetosDePrueba.madrid(), ObjetosDePrueba.AHORA.minusSeconds(24 * 3600))
                .construir();

        noSeDisparo(regla.evaluar(ObjetosDePrueba.contexto(desdeTokio, perfil)));
    }

    @Test
    @DisplayName("no dispara cuando el titular opera desde donde se le vio")
    void noDisparaEnLaMismaCiudad() {
        noSeDisparo(regla.evaluar(ObjetosDePrueba.contexto(ObjetosDePrueba.transaccionWeb())));
    }

    @Test
    @DisplayName("no aplica si la transacción no reporta geolocalización")
    void noAplicaSinGeolocalizacion() {
        Transaccion sinOrigen = ObjetosDePrueba.transaccion(
                ObjetosDePrueba.euros("50.00"),
                ObjetosDePrueba.tarjetaNoPresente(),
                ObjetosDePrueba.comercio(),
                com.centinela.fraude.dominio.modelo.vo.Canal.API,
                null,
                null);

        assertThat(noAplico(regla.evaluar(ObjetosDePrueba.contexto(sinOrigen)))).contains("geolocalización");
    }

    @Test
    @DisplayName("se degrada, no aprueba, cuando el perfil no tiene posición previa")
    void seDegradaSinPosicionPrevia() {
        // Distinción crítica: sin posición anterior la regla NO puede afirmar que todo está
        // bien. Si devolviera "no disparada", un cliente sin historial parecería verificado.
        var perfil = PerfilDePrueba.unPerfil().sinPosicionPrevia().construir();

        assertThat(seDegrado(regla.evaluar(ObjetosDePrueba.contexto(ObjetosDePrueba.transaccionWeb(), perfil))))
                .contains("posición");
    }

    @Test
    @DisplayName("respeta el peso configurado en la política")
    void respetaElPesoDeLaPolitica() {
        Transaccion desdeTokio = ObjetosDePrueba.transaccionDesde(ObjetosDePrueba.tokio());
        var perfil = PerfilDePrueba.unPerfil()
                .vistoEn(ObjetosDePrueba.madrid(), ObjetosDePrueba.AHORA.minusSeconds(600))
                .construir();
        PoliticaDeRiesgo recalibrada =
                PoliticaDeRiesgo.porDefecto().conAportes(Map.of(ReglaViajeImposible.CODIGO, 999));

        SenalDeRiesgo senal =
                seDisparo(regla.evaluar(ObjetosDePrueba.contexto(desdeTokio, perfil, Map.of(), recalibrada)));

        assertThat(senal.aporte()).isEqualTo(999);
    }
}
