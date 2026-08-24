package com.centinela.fraude.dominio.modelo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.centinela.fraude.dominio.ObjetosDePrueba;
import com.centinela.fraude.dominio.excepcion.ViolacionDeInvariante;
import com.centinela.fraude.dominio.modelo.vo.Canal;
import com.centinela.fraude.dominio.modelo.vo.IdTransaccion;
import com.centinela.fraude.dominio.modelo.vo.InstrumentoDePago;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Transaccion")
class TransaccionTest {

    @Test
    @DisplayName("particiona por cliente para que sus operaciones se procesen en orden")
    void particionaPorCliente() {
        Transaccion transaccion = ObjetosDePrueba.transaccionWeb();

        assertThat(transaccion.claveDeParticion()).isEqualTo(transaccion.cliente().valor());
    }

    @Test
    @DisplayName("una tarjeta presente es incompatible con un canal remoto")
    void rechazaTarjetaPresenteEnCanalRemoto() {
        assertThatThrownBy(() -> Transaccion.de(
                        IdTransaccion.de("tx-0002"),
                        ObjetosDePrueba.cliente(),
                        ObjetosDePrueba.euros("50.00"),
                        ObjetosDePrueba.tarjetaPresente(),
                        ObjetosDePrueba.comercio(),
                        Canal.WEB,
                        ObjetosDePrueba.AHORA))
                .isInstanceOf(ViolacionDeInvariante.class)
                .hasMessageContaining("WEB");
    }

    @Test
    @DisplayName("acepta una tarjeta presente en un canal con presencia física")
    void aceptaTarjetaPresenteEnPos() {
        Transaccion transaccion = Transaccion.de(
                IdTransaccion.de("tx-0003"),
                ObjetosDePrueba.cliente(),
                ObjetosDePrueba.euros("50.00"),
                ObjetosDePrueba.tarjetaPresente(),
                ObjetosDePrueba.comercio(),
                Canal.POS,
                ObjetosDePrueba.AHORA);

        assertThat(transaccion.esNoPresencial()).isFalse();
    }

    @Test
    @DisplayName("rechaza un importe cero")
    void rechazaImporteCero() {
        assertThatThrownBy(() -> Transaccion.de(
                        IdTransaccion.de("tx-0004"),
                        ObjetosDePrueba.cliente(),
                        ObjetosDePrueba.euros("0.00"),
                        ObjetosDePrueba.tarjetaNoPresente(),
                        ObjetosDePrueba.comercio(),
                        Canal.WEB,
                        ObjetosDePrueba.AHORA))
                .isInstanceOf(ViolacionDeInvariante.class);
    }

    @Test
    @DisplayName("normaliza los datos opcionales ausentes a Optional vacío")
    void normalizaOpcionalesNulos() {
        Transaccion transaccion = new Transaccion(
                IdTransaccion.de("tx-0005"),
                ObjetosDePrueba.cliente(),
                ObjetosDePrueba.euros("50.00"),
                ObjetosDePrueba.tarjetaNoPresente(),
                ObjetosDePrueba.comercio(),
                Canal.API,
                null,
                null,
                ObjetosDePrueba.AHORA);

        assertThat(transaccion.origen()).isEmpty();
        assertThat(transaccion.dispositivo()).isEmpty();
    }

    @Test
    @DisplayName("detecta operación transfronteriza comparando origen y comercio")
    void detectaCruceDeFrontera() {
        Transaccion nacional = ObjetosDePrueba.transaccionWeb();
        Transaccion internacional = new Transaccion(
                IdTransaccion.de("tx-0006"),
                ObjetosDePrueba.cliente(),
                ObjetosDePrueba.euros("50.00"),
                ObjetosDePrueba.tarjetaNoPresente(),
                ObjetosDePrueba.comercio(),
                Canal.WEB,
                Optional.of(ObjetosDePrueba.tokio()),
                Optional.empty(),
                ObjetosDePrueba.AHORA);

        assertThat(nacional.cruzaFrontera()).isFalse();
        assertThat(internacional.cruzaFrontera()).isTrue();
    }

    @Test
    @DisplayName("la antigüedad nunca es negativa aunque el evento venga del futuro")
    void antiguedadNoNegativa() {
        Transaccion transaccion = ObjetosDePrueba.transaccionWeb();

        assertThat(transaccion.antiguedadRespectoA(ObjetosDePrueba.AHORA.minusSeconds(30)))
                .isEqualTo(Duration.ZERO);
        assertThat(transaccion.antiguedadRespectoA(ObjetosDePrueba.AHORA.plusSeconds(30)))
                .isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("la descripción para logs no filtra el cliente en claro ni el PAN")
    void descripcionSeguraNoFiltraDatos() {
        Transaccion transaccion = ObjetosDePrueba.transaccionWeb();
        String descripcion = transaccion.descripcionSegura();

        assertThat(descripcion).doesNotContain(transaccion.cliente().valor());
        assertThat(descripcion).contains("******");
    }

    @Test
    @DisplayName("el riesgo intrínseco distingue tarjeta presente de tarjeta no presente")
    void riesgoIntrinsecoDelInstrumento() {
        InstrumentoDePago noPresente = ObjetosDePrueba.tarjetaNoPresente();
        InstrumentoDePago presente = ObjetosDePrueba.tarjetaPresente();

        assertThat(noPresente.riesgoIntrinseco()).isGreaterThan(presente.riesgoIntrinseco());
    }
}
