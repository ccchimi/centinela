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
import com.centinela.fraude.dominio.modelo.vo.ClaveDeVelocidad;
import com.centinela.fraude.dominio.modelo.vo.DimensionDeVelocidad;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("Reglas de ráfaga")
class ReglasDeRafagaTest {

    /** Umbrales de la política por defecto: 8 operaciones por cliente, 6 intentos por dispositivo. */
    private static final ClaveDeVelocidad CLAVE_CLIENTE =
            ClaveDeVelocidad.de(DimensionDeVelocidad.TRANSACCIONES_POR_CLIENTE, ObjetosDePrueba.cliente());
    private static final ClaveDeVelocidad CLAVE_DISPOSITIVO =
            ClaveDeVelocidad.de(DimensionDeVelocidad.INTENTOS_POR_DISPOSITIVO, ObjetosDePrueba.DISPOSITIVO_CONOCIDO);

    @Nested
    @DisplayName("por cliente")
    class PorCliente {

        private final ReglaRafagaPorCliente regla = new ReglaRafagaPorCliente();

        @Test
        @DisplayName("declara el contador del titular antes de evaluar")
        void declaraSuContador() {
            assertThat(regla.contadoresQueNecesita(ObjetosDePrueba.transaccionWeb())).containsExactly(CLAVE_CLIENTE);
        }

        @Test
        @DisplayName("no dispara dentro del umbral")
        void noDisparaDentroDelUmbral() {
            noSeDisparo(regla.evaluar(conContador(CLAVE_CLIENTE, 8L)));
        }

        @Test
        @DisplayName("dispara al superar el umbral")
        void disparaAlSuperarElUmbral() {
            SenalDeRiesgo senal = seDisparo(regla.evaluar(conContador(CLAVE_CLIENTE, 9L)));

            assertThat(senal.codigo()).isEqualTo(ReglaRafagaPorCliente.CODIGO);
            assertThat(senal.descripcion()).contains("9").contains("60 s").contains("8");
        }

        @ParameterizedTest(name = "{0} operaciones sobre un umbral de 8 aportan {1}")
        @CsvSource({
            "9, 225", // exceso de 1 sobre 8 -> factor 1,125
            "12, 300", // exceso de 4        -> factor 1,5
            "16, 400", // exceso de 8        -> factor 2
            "24, 600", // exceso de 16       -> factor 3 (techo)
            "500, 600" // saturado en el techo
        })
        @DisplayName("escala el aporte con el exceso y satura en el triple")
        void escalaConElExceso(long observadas, int aporteEsperado) {
            assertThat(seDisparo(regla.evaluar(conContador(CLAVE_CLIENTE, observadas))).aporte())
                    .isEqualTo(aporteEsperado);
        }

        @Test
        @DisplayName("se degrada si el contador no estuvo disponible")
        void seDegradaSinContador() {
            // El caso que importa: con la caché caída el mapa llega vacío. Si esto se leyera
            // como "cero operaciones", el motor aprobaría la ráfaga creyéndose sano.
            assertThat(seDegrado(regla.evaluar(ObjetosDePrueba.contexto(ObjetosDePrueba.transaccionWeb()))))
                    .contains("no estuvo disponible");
        }

        @Test
        @DisplayName("distingue contador ausente de contador en cero")
        void ausenteNoEsCero() {
            noSeDisparo(regla.evaluar(conContador(CLAVE_CLIENTE, 0L)));
            seDegrado(regla.evaluar(ObjetosDePrueba.contexto(ObjetosDePrueba.transaccionWeb())));
        }
    }

    @Nested
    @DisplayName("por dispositivo")
    class PorDispositivo {

        private final ReglaRafagaPorDispositivo regla = new ReglaRafagaPorDispositivo();

        @Test
        @DisplayName("declara el contador del dispositivo")
        void declaraSuContador() {
            assertThat(regla.contadoresQueNecesita(ObjetosDePrueba.transaccionWeb()))
                    .containsExactly(CLAVE_DISPOSITIVO);
        }

        @Test
        @DisplayName("dispara al superar el umbral de intentos: firma del card testing")
        void disparaAnteCardTesting() {
            SenalDeRiesgo senal = seDisparo(regla.evaluar(conContador(CLAVE_DISPOSITIVO, 7L)));

            assertThat(senal.codigo()).isEqualTo(ReglaRafagaPorDispositivo.CODIGO);
            assertThat(senal.descripcion()).contains("intentos desde el dispositivo");
        }

        @Test
        @DisplayName("no aplica ni pide contador si la transacción no trae dispositivo")
        void noAplicaSinDispositivo() {
            Transaccion enCajero = ObjetosDePrueba.transaccionEn(Canal.CAJERO);

            assertThat(regla.contadoresQueNecesita(enCajero)).isEmpty();
            assertThat(noAplico(regla.evaluar(ObjetosDePrueba.contexto(enCajero)))).contains("huella de dispositivo");
        }
    }

    private static com.centinela.fraude.dominio.regla.ContextoDeEvaluacion conContador(
            ClaveDeVelocidad clave, long valor) {
        return ObjetosDePrueba.contexto(
                ObjetosDePrueba.transaccionWeb(), PerfilDePrueba.unPerfil().construir(), Map.of(clave, valor));
    }
}
