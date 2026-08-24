package com.centinela.fraude.dominio.regla.catalogo;

import static com.centinela.fraude.dominio.regla.Resultados.noSeDisparo;
import static com.centinela.fraude.dominio.regla.Resultados.seDisparo;
import static org.assertj.core.api.Assertions.assertThat;

import com.centinela.fraude.dominio.ObjetosDePrueba;
import com.centinela.fraude.dominio.modelo.SenalDeRiesgo;
import com.centinela.fraude.dominio.modelo.vo.Canal;
import com.centinela.fraude.dominio.modelo.vo.InstrumentoDePago;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ReglaInstrumentoDeRiesgo")
class ReglaInstrumentoDeRiesgoTest {

    private final ReglaInstrumentoDeRiesgo regla = new ReglaInstrumentoDeRiesgo();

    @Test
    @DisplayName("no dispara con tarjeta no presente: es el caso base del comercio electrónico")
    void noDisparaConTarjetaNoPresente() {
        // Casi todo el volumen de los canales remotos es no presente. Disparar aquí sumaría el
        // mismo aporte a casi todas las transacciones: no discrimina, solo desplaza la
        // distribución y obliga a subir todos los umbrales. El riesgo real de la tarjeta no
        // presente lo capturan las reglas de comportamiento.
        noSeDisparo(regla.evaluar(
                ObjetosDePrueba.contexto(ObjetosDePrueba.transaccionCon(ObjetosDePrueba.tarjetaNoPresente()))));
    }

    @Test
    @DisplayName("tampoco dispara con tarjeta leída físicamente")
    void noDisparaConTarjetaPresente() {
        var enPos = ObjetosDePrueba.transaccion(
                ObjetosDePrueba.euros("120.00"),
                ObjetosDePrueba.tarjetaPresente(),
                ObjetosDePrueba.comercio(),
                Canal.POS,
                ObjetosDePrueba.madrid(),
                null);

        noSeDisparo(regla.evaluar(ObjetosDePrueba.contexto(enPos)));
    }

    @Test
    @DisplayName("no dispara con billetera tokenizada")
    void noDisparaConBilleteraTokenizada() {
        noSeDisparo(regla.evaluar(
                ObjetosDePrueba.contexto(ObjetosDePrueba.transaccionCon(ObjetosDePrueba.billeteraTokenizada()))));
    }

    @Test
    @DisplayName("dispara con billetera sin tokenizar y lo explica")
    void disparaConBilleteraSinTokenizar() {
        SenalDeRiesgo senal = seDisparo(regla.evaluar(
                ObjetosDePrueba.contexto(ObjetosDePrueba.transaccionCon(ObjetosDePrueba.billeteraSinTokenizar()))));

        assertThat(senal.aporte()).isEqualTo(90);
        assertThat(senal.descripcion()).contains("MonederoX").contains("sin token");
    }

    @Test
    @DisplayName("dispara con transferencia y señala que es irreversible")
    void disparaConTransferencia() {
        SenalDeRiesgo senal = seDisparo(
                regla.evaluar(ObjetosDePrueba.contexto(ObjetosDePrueba.transaccionCon(ObjetosDePrueba.transferencia()))));

        assertThat(senal.aporte()).isEqualTo(150);
        assertThat(senal.descripcion()).contains("BSCHESMM").contains("irreversible");
    }

    @Test
    @DisplayName("la señal no filtra el identificador del dispositivo de la billetera")
    void laSenalNoFiltraDatosDelDispositivo() {
        InstrumentoDePago billetera = ObjetosDePrueba.billeteraSinTokenizar();
        SenalDeRiesgo senal =
                seDisparo(regla.evaluar(ObjetosDePrueba.contexto(ObjetosDePrueba.transaccionCon(billetera))));

        assertThat(senal.descripcion()).doesNotContain(ObjetosDePrueba.DISPOSITIVO_CONOCIDO.huella());
    }
}
