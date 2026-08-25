package com.centinela.fraude.dominio.modelo;

import static org.assertj.core.api.Assertions.assertThat;

import com.centinela.fraude.dominio.ObjetosDePrueba;
import com.centinela.fraude.dominio.PerfilDePrueba;
import com.centinela.fraude.dominio.modelo.vo.Canal;
import com.centinela.fraude.dominio.modelo.vo.Comercio;
import com.centinela.fraude.dominio.modelo.vo.Dinero;
import com.centinela.fraude.dominio.modelo.vo.IdDispositivo;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("Aprendizaje del perfil")
class AprendizajeDelPerfilTest {

    @Test
    @DisplayName("incorpora la transacción y sube la versión")
    void incorporaLaTransaccion() {
        PerfilDeComportamiento base =
                PerfilDePrueba.unPerfil().conTransacciones(100).enVersion(4).construir();

        PerfilDeComportamiento aprendido = base.aprendiendoDe(
                        ObjetosDePrueba.transaccionWeb(), Decision.APROBAR, ObjetosDePrueba.AHORA)
                .orElseThrow();

        assertThat(aprendido.transaccionesObservadas()).isEqualTo(101);
        assertThat(aprendido.version()).isEqualTo(5);
        assertThat(aprendido.ultimaActividad()).contains(ObjetosDePrueba.AHORA);
        assertThat(aprendido.actualizadoEn()).isEqualTo(ObjetosDePrueba.AHORA);
    }

    @ParameterizedTest
    @EnumSource(value = Decision.class, names = {"RECHAZAR", "DESAFIAR", "REVISAR"})
    @DisplayName("no aprende de nada que no se aprobara sin fricción")
    void noAprendeDeLoSospechoso(Decision decision) {
        // La guarda que impide que un atacante entrene el perfil a su favor. Si una compra
        // rechazada desde Tokio hiciera habitual a Japón, la siguiente ya no dispararía.
        PerfilDeComportamiento base = PerfilDePrueba.unPerfil().construir();

        assertThat(base.aprendiendoDe(ObjetosDePrueba.transaccionWeb(), decision, ObjetosDePrueba.AHORA))
                .isEmpty();
    }

    @Test
    @DisplayName("recalcula la media móvil con el importe nuevo")
    void recalculaLaMedia() {
        // Media 100 sobre 99 operaciones; entra una de 1000 => (100*99 + 1000) / 100 = 109
        PerfilDeComportamiento base =
                PerfilDePrueba.unPerfil().conMedia("100.00").conTransacciones(99).construir();

        PerfilDeComportamiento aprendido = base.aprendiendoDe(
                        ObjetosDePrueba.transaccionDe(ObjetosDePrueba.euros("1000.00")),
                        Decision.APROBAR,
                        ObjetosDePrueba.AHORA)
                .orElseThrow();

        assertThat(aprendido.importeMedio()).isEqualTo(Dinero.de("109.00", "EUR"));
    }

    @Test
    @DisplayName("actualiza el máximo histórico sólo cuando se supera")
    void actualizaElMaximo() {
        PerfilDeComportamiento base = PerfilDePrueba.unPerfil().construir(); // máximo 500

        PerfilDeComportamiento conMayor = base.aprendiendoDe(
                        ObjetosDePrueba.transaccionDe(ObjetosDePrueba.euros("900.00")),
                        Decision.APROBAR,
                        ObjetosDePrueba.AHORA)
                .orElseThrow();
        PerfilDeComportamiento conMenor = base.aprendiendoDe(
                        ObjetosDePrueba.transaccionDe(ObjetosDePrueba.euros("50.00")),
                        Decision.APROBAR,
                        ObjetosDePrueba.AHORA)
                .orElseThrow();

        assertThat(conMayor.importeMaximoHistorico()).isEqualTo(ObjetosDePrueba.euros("900.00"));
        assertThat(conMenor.importeMaximoHistorico()).isEqualTo(base.importeMaximoHistorico());
    }

    @Test
    @DisplayName("no toca los agregados monetarios si la moneda no coincide")
    void monedaDistintaNoContamina() {
        // Sumar dólares a una media en euros produciría un número sin significado, y la regla
        // de desviación decidiría sobre él. Se prefiere no aprender el importe a aprender mal.
        PerfilDeComportamiento base = PerfilDePrueba.unPerfil().conMedia("100.00").construir();
        var enDolares = ObjetosDePrueba.transaccionDe(Dinero.de("5000.00", "USD"));

        PerfilDeComportamiento aprendido =
                base.aprendiendoDe(enDolares, Decision.APROBAR, ObjetosDePrueba.AHORA).orElseThrow();

        assertThat(aprendido.importeMedio()).isEqualTo(base.importeMedio());
        assertThat(aprendido.importeMaximoHistorico()).isEqualTo(base.importeMaximoHistorico());
        // Pero lo no monetario sí se incorpora: el comercio y el dispositivo siguen valiendo.
        assertThat(aprendido.transaccionesObservadas()).isEqualTo(base.transaccionesObservadas() + 1);
    }

    @Test
    @DisplayName("incorpora país, comercio y dispositivo nuevos")
    void incorporaElContexto() {
        PerfilDeComportamiento base =
                PerfilDePrueba.unPerfil().conPaises("ES").conComercios("com-100").construir();
        var enJapon = ObjetosDePrueba.transaccion(
                ObjetosDePrueba.euros("120.00"),
                ObjetosDePrueba.tarjetaNoPresente(),
                Comercio.de("com-jp", "Tienda", "5942", "JP"),
                Canal.WEB,
                ObjetosDePrueba.tokio(),
                ObjetosDePrueba.DISPOSITIVO_NUEVO);

        PerfilDeComportamiento aprendido =
                base.aprendiendoDe(enJapon, Decision.APROBAR, ObjetosDePrueba.AHORA).orElseThrow();

        assertThat(aprendido.paisesHabituales()).containsExactlyInAnyOrder("ES", "JP");
        assertThat(aprendido.comerciosHabituales()).containsExactlyInAnyOrder("com-100", "com-jp");
        assertThat(aprendido.conoceElDispositivo(ObjetosDePrueba.DISPOSITIVO_NUEVO)).isTrue();
        assertThat(aprendido.ultimaPosicion()).contains(ObjetosDePrueba.tokio());
    }

    @Test
    @DisplayName("conserva la última posición conocida si la transacción no reporta ninguna")
    void sinGeolocalizacionConservaLaAnterior() {
        PerfilDeComportamiento base = PerfilDePrueba.unPerfil().construir();
        var porApi = ObjetosDePrueba.transaccion(
                ObjetosDePrueba.euros("120.00"),
                ObjetosDePrueba.tarjetaNoPresente(),
                ObjetosDePrueba.comercio(),
                Canal.API,
                null,
                null);

        PerfilDeComportamiento aprendido =
                base.aprendiendoDe(porApi, Decision.APROBAR, ObjetosDePrueba.AHORA).orElseThrow();

        assertThat(aprendido.ultimaPosicion()).isEqualTo(base.ultimaPosicion());
        assertThat(aprendido.paisesHabituales()).isEqualTo(base.paisesHabituales());
    }

    @Test
    @DisplayName("los conjuntos están acotados y descartan lo más antiguo")
    void losConjuntosNoCrecenSinLimite() {
        // Sin cota, un cliente de años acabaría con miles de dispositivos y la fila superaría el
        // límite de tamaño de DynamoDB. Este test fija el tope y el criterio de descarte.
        PerfilDeComportamiento perfil = PerfilDePrueba.unPerfil().conDispositivos().construir();

        for (int i = 0; i < PerfilDeComportamiento.MAXIMO_DISPOSITIVOS + 10; i++) {
            var transaccion = ObjetosDePrueba.transaccion(
                    ObjetosDePrueba.euros("50.00"),
                    ObjetosDePrueba.tarjetaNoPresente(),
                    ObjetosDePrueba.comercio(),
                    Canal.WEB,
                    ObjetosDePrueba.madrid(),
                    IdDispositivo.de("disp-%03d".formatted(i)));
            perfil = perfil.aprendiendoDe(transaccion, Decision.APROBAR, ObjetosDePrueba.AHORA)
                    .orElseThrow();
        }

        assertThat(perfil.dispositivosConocidos()).hasSize(PerfilDeComportamiento.MAXIMO_DISPOSITIVOS);
        assertThat(perfil.conoceElDispositivo(IdDispositivo.de("disp-000")))
                .as("el más antiguo debería haberse descartado")
                .isFalse();
        assertThat(perfil.conoceElDispositivo(IdDispositivo.de("disp-059")))
                .as("el más reciente debería seguir ahí")
                .isTrue();
    }

    @Test
    @DisplayName("un dispositivo ya conocido no reordena ni desplaza a nadie")
    void repetirNoAlteraElConjunto() {
        PerfilDeComportamiento base = PerfilDePrueba.unPerfil()
                .conDispositivos(ObjetosDePrueba.DISPOSITIVO_CONOCIDO)
                .construir();

        PerfilDeComportamiento aprendido = base.aprendiendoDe(
                        ObjetosDePrueba.transaccionWeb(), Decision.APROBAR, ObjetosDePrueba.AHORA)
                .orElseThrow();

        assertThat(aprendido.dispositivosConocidos()).isEqualTo(base.dispositivosConocidos());
    }

    @Test
    @DisplayName("un cliente nuevo pasa a tener perfil con una sola operación")
    void desdeCero() {
        PerfilDeComportamiento nuevo = PerfilDePrueba.recienCreado();

        PerfilDeComportamiento aprendido = nuevo.aprendiendoDe(
                        ObjetosDePrueba.transaccionWeb(), Decision.APROBAR, ObjetosDePrueba.AHORA)
                .orElseThrow();

        assertThat(aprendido.transaccionesObservadas()).isEqualTo(1);
        assertThat(aprendido.version()).isEqualTo(1);
        assertThat(aprendido.importeMedio()).isEqualTo(ObjetosDePrueba.euros("120.00"));
        assertThat(aprendido.esFiable()).isFalse();
        assertThat(Optional.of(aprendido.paisesHabituales())).get().asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.collection(String.class))
                .containsExactly("ES");
    }
}
