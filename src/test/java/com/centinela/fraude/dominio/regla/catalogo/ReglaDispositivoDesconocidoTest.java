package com.centinela.fraude.dominio.regla.catalogo;

import static com.centinela.fraude.dominio.regla.Resultados.noAplico;
import static com.centinela.fraude.dominio.regla.Resultados.noSeDisparo;
import static com.centinela.fraude.dominio.regla.Resultados.seDegrado;
import static com.centinela.fraude.dominio.regla.Resultados.seDisparo;
import static org.assertj.core.api.Assertions.assertThat;

import com.centinela.fraude.dominio.ObjetosDePrueba;
import com.centinela.fraude.dominio.PerfilDePrueba;
import com.centinela.fraude.dominio.modelo.Transaccion;
import com.centinela.fraude.dominio.modelo.vo.Canal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("ReglaDispositivoDesconocido")
class ReglaDispositivoDesconocidoTest {

    private final ReglaDispositivoDesconocido regla = new ReglaDispositivoDesconocido();

    @Test
    @DisplayName("dispara ante un dispositivo que el titular nunca ha usado")
    void disparaAnteDispositivoNuevo() {
        Transaccion desdeOtroMovil = ObjetosDePrueba.transaccion(
                ObjetosDePrueba.euros("120.00"),
                ObjetosDePrueba.tarjetaNoPresente(),
                ObjetosDePrueba.comercio(),
                Canal.MOVIL,
                ObjetosDePrueba.madrid(),
                ObjetosDePrueba.DISPOSITIVO_NUEVO);

        var senal = seDisparo(regla.evaluar(ObjetosDePrueba.contexto(desdeOtroMovil)));

        assertThat(senal.codigo()).isEqualTo(ReglaDispositivoDesconocido.CODIGO);
        assertThat(senal.aporte()).isEqualTo(180);
    }

    @Test
    @DisplayName("no dispara con un dispositivo ya conocido")
    void noDisparaConDispositivoConocido() {
        noSeDisparo(regla.evaluar(ObjetosDePrueba.contexto(ObjetosDePrueba.transaccionWeb())));
    }

    @ParameterizedTest
    @EnumSource(value = Canal.class, names = {"POS", "CAJERO", "API", "TELEFONICO"})
    @DisplayName("no aplica en canales que no reportan huella de dispositivo")
    void noAplicaEnCanalesSinHuella(Canal canal) {
        assertThat(noAplico(regla.evaluar(ObjetosDePrueba.contexto(ObjetosDePrueba.transaccionEn(canal)))))
                .contains(canal.name());
    }

    @Test
    @DisplayName("se degrada si el canal debía reportar huella y no llegó")
    void seDegradaSiFaltaLaHuellaEsperada() {
        // Síntoma habitual de un SDK de cliente roto. Confundirlo con "dispositivo conocido"
        // desactivaría el control sin que nadie lo notara.
        Transaccion sinHuella = ObjetosDePrueba.transaccion(
                ObjetosDePrueba.euros("120.00"),
                ObjetosDePrueba.tarjetaNoPresente(),
                ObjetosDePrueba.comercio(),
                Canal.WEB,
                ObjetosDePrueba.madrid(),
                null);

        assertThat(seDegrado(regla.evaluar(ObjetosDePrueba.contexto(sinHuella)))).contains("WEB");
    }

    @Test
    @DisplayName("se degrada con un cliente nuevo, para el que todo dispositivo es desconocido")
    void seDegradaConClienteNuevo() {
        var perfil = PerfilDePrueba.unPerfil().sinHistorialSuficiente().construir();

        assertThat(seDegrado(regla.evaluar(ObjetosDePrueba.contexto(ObjetosDePrueba.transaccionWeb(), perfil))))
                .contains("dispositivos conocidos");
    }

    @Test
    @DisplayName("no pide contadores de velocidad")
    void noPideContadores() {
        assertThat(regla.contadoresQueNecesita(ObjetosDePrueba.transaccionWeb())).isEmpty();
    }
}
