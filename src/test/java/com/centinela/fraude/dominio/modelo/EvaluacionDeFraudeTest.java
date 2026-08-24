package com.centinela.fraude.dominio.modelo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.centinela.fraude.dominio.ObjetosDePrueba;
import com.centinela.fraude.dominio.excepcion.ViolacionDeInvariante;
import com.centinela.fraude.dominio.modelo.vo.IdTransaccion;
import com.centinela.fraude.dominio.modelo.vo.PuntajeDeRiesgo;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("EvaluacionDeFraude")
class EvaluacionDeFraudeTest {

    private static final IdTransaccion TX = IdTransaccion.de("tx-0001");
    private static final Duration LATENCIA = Duration.ofMillis(37);

    @Test
    @DisplayName("sin señales, la transacción es de riesgo bajo y se aprueba")
    void sinSenalesSeAprueba() {
        EvaluacionDeFraude evaluacion =
                EvaluacionDeFraude.apartirDe(TX, List.of(), ObjetosDePrueba.AHORA, LATENCIA);

        assertThat(evaluacion.puntaje()).isEqualTo(PuntajeDeRiesgo.NULO);
        assertThat(evaluacion.nivel()).isEqualTo(NivelDeRiesgo.BAJO);
        assertThat(evaluacion.decision()).isEqualTo(Decision.APROBAR);
        assertThat(evaluacion.bloqueada()).isFalse();
    }

    @Test
    @DisplayName("acumula los aportes de las señales y deriva el nivel y la decisión")
    void acumulaAportes() {
        EvaluacionDeFraude evaluacion = EvaluacionDeFraude.apartirDe(
                TX,
                List.of(
                        SenalDeRiesgo.de("VIAJE_IMPOSIBLE", "Desplazamiento no factible", 400),
                        SenalDeRiesgo.de("DISPOSITIVO_DESCONOCIDO", "Dispositivo nunca visto", 250),
                        SenalDeRiesgo.de("COMERCIO_ALTO_RIESGO", "MCC de categoría sensible", 60)),
                ObjetosDePrueba.AHORA,
                LATENCIA);

        assertThat(evaluacion.puntaje().valor()).isEqualTo(710);
        assertThat(evaluacion.nivel()).isEqualTo(NivelDeRiesgo.ALTO);
        assertThat(evaluacion.decision()).isEqualTo(Decision.REVISAR);
    }

    @Test
    @DisplayName("ordena la evidencia de mayor a menor aporte para el analista")
    void ordenaLaEvidencia() {
        EvaluacionDeFraude evaluacion = EvaluacionDeFraude.apartirDe(
                TX,
                List.of(
                        SenalDeRiesgo.de("MENOR", "Aporte pequeño", 10),
                        SenalDeRiesgo.de("MAYOR", "Aporte grande", 900)),
                ObjetosDePrueba.AHORA,
                LATENCIA);

        assertThat(evaluacion.senales()).extracting(SenalDeRiesgo::codigo).containsExactly("MAYOR", "MENOR");
        assertThat(evaluacion.senalPrincipal()).map(SenalDeRiesgo::codigo).contains("MAYOR");
    }

    @Test
    @DisplayName("el puntaje satura en el máximo en vez de desbordar")
    void elPuntajeSatura() {
        EvaluacionDeFraude evaluacion = EvaluacionDeFraude.apartirDe(
                TX,
                List.of(
                        SenalDeRiesgo.de("UNO", "Señal fuerte", 800),
                        SenalDeRiesgo.de("DOS", "Otra señal fuerte", 800)),
                ObjetosDePrueba.AHORA,
                LATENCIA);

        assertThat(evaluacion.puntaje().valor()).isEqualTo(PuntajeDeRiesgo.MAXIMO);
        assertThat(evaluacion.nivel()).isEqualTo(NivelDeRiesgo.CRITICO);
        assertThat(evaluacion.bloqueada()).isTrue();
    }

    @Test
    @DisplayName("la lista de señales es inmutable una vez tomada la decisión")
    void laEvidenciaEsInmutable() {
        EvaluacionDeFraude evaluacion = EvaluacionDeFraude.apartirDe(
                TX, List.of(SenalDeRiesgo.de("UNA", "Señal", 100)), ObjetosDePrueba.AHORA, LATENCIA);

        assertThatThrownBy(() -> evaluacion.senales().add(SenalDeRiesgo.de("OTRA", "Añadida a posteriori", 100)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("rechaza una evaluación cuyo nivel contradice su puntaje")
    void rechazaNivelIncoherente() {
        assertThatThrownBy(() -> new EvaluacionDeFraude(
                        TX,
                        PuntajeDeRiesgo.de(900),
                        NivelDeRiesgo.BAJO,
                        Decision.APROBAR,
                        List.of(),
                        List.of(),
                        ObjetosDePrueba.AHORA,
                        LATENCIA))
                .isInstanceOf(ViolacionDeInvariante.class)
                .hasMessageContaining("CRITICO");
    }

    @Test
    @DisplayName("permite endurecer la decisión sin alterar la evidencia")
    void permiteEndurecerLaDecision() {
        EvaluacionDeFraude original =
                EvaluacionDeFraude.apartirDe(TX, List.of(), ObjetosDePrueba.AHORA, LATENCIA);

        EvaluacionDeFraude endurecida = original.conDecision(Decision.RECHAZAR);

        assertThat(endurecida.decision()).isEqualTo(Decision.RECHAZAR);
        assertThat(endurecida.puntaje()).isEqualTo(original.puntaje());
        assertThat(endurecida.senales()).isEqualTo(original.senales());
        assertThat(original.decision()).isEqualTo(Decision.APROBAR);
    }
}
