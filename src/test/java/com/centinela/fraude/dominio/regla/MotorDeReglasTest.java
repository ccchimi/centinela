package com.centinela.fraude.dominio.regla;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.centinela.fraude.dominio.ObjetosDePrueba;
import com.centinela.fraude.dominio.excepcion.ViolacionDeInvariante;
import com.centinela.fraude.dominio.modelo.SenalDeRiesgo;
import com.centinela.fraude.dominio.modelo.Transaccion;
import com.centinela.fraude.dominio.modelo.vo.ClaveDeVelocidad;
import com.centinela.fraude.dominio.modelo.vo.DimensionDeVelocidad;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pruebas del agregador. Usa reglas de mentira escritas a mano en vez de mocks: son tres líneas,
 * se leen mejor que un {@code when(...).thenReturn(...)} y no hay comportamiento que simular.
 */
@DisplayName("MotorDeReglas")
class MotorDeReglasTest {

    private static final ContextoDeEvaluacion CONTEXTO = ObjetosDePrueba.contexto(ObjetosDePrueba.transaccionWeb());

    /** Regla de laboratorio: devuelve lo que se le diga. */
    private static ReglaDeFraude regla(String codigo, Function<ContextoDeEvaluacion, ResultadoDeRegla> respuesta) {
        return new ReglaDeFraude() {
            @Override
            public String codigo() {
                return codigo;
            }

            @Override
            public ResultadoDeRegla evaluar(ContextoDeEvaluacion contexto) {
                return respuesta.apply(contexto);
            }
        };
    }

    private static ReglaDeFraude reglaQueDispara(String codigo, int aporte) {
        return regla(codigo, _ -> ResultadoDeRegla.disparada(codigo, "evidencia de prueba", aporte));
    }

    @Nested
    @DisplayName("construcción")
    class Construccion {

        @Test
        @DisplayName("rechaza un catálogo vacío")
        void rechazaCatalogoVacio() {
            assertThatThrownBy(() -> new MotorDeReglas(List.of())).isInstanceOf(ViolacionDeInvariante.class);
        }

        @Test
        @DisplayName("rechaza códigos de regla duplicados")
        void rechazaCodigosDuplicados() {
            List<ReglaDeFraude> conDuplicado = List.of(reglaQueDispara("REPE", 10), reglaQueDispara("REPE", 20));

            assertThatThrownBy(() -> new MotorDeReglas(conDuplicado))
                    .isInstanceOf(ViolacionDeInvariante.class)
                    .hasMessageContaining("REPE");
        }

        @Test
        @DisplayName("expone los códigos del catálogo para métricas")
        void exponeLosCodigos() {
            MotorDeReglas motor = new MotorDeReglas(List.of(reglaQueDispara("UNA", 10), reglaQueDispara("OTRA", 20)));

            assertThat(motor.codigos()).containsExactly("UNA", "OTRA");
            assertThat(motor.cantidadDeReglas()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("agregación")
    class Agregacion {

        @Test
        @DisplayName("recoge como evidencia solo las reglas disparadas")
        void recogeSoloLasDisparadas() {
            MotorDeReglas motor = new MotorDeReglas(List.of(
                    reglaQueDispara("DISPARA", 100),
                    regla("NO_DISPARA", _ -> ResultadoDeRegla.noDisparada()),
                    regla("NO_APLICA", _ -> ResultadoDeRegla.noAplicable("otro canal"))));

            ResultadoDelMotor resultado = motor.ejecutar(CONTEXTO);

            assertThat(resultado.senales()).extracting(SenalDeRiesgo::codigo).containsExactly("DISPARA");
            assertThat(resultado.reglasEvaluadas()).isEqualTo(3);
            assertThat(resultado.reglasDisparadas()).isEqualTo(1);
        }

        @Test
        @DisplayName("no genera advertencias cuando una regla simplemente no aplica")
        void noAplicableNoEsAdvertencia() {
            MotorDeReglas motor =
                    new MotorDeReglas(List.of(regla("NO_APLICA", _ -> ResultadoDeRegla.noAplicable("canal POS"))));

            ResultadoDelMotor resultado = motor.ejecutar(CONTEXTO);

            assertThat(resultado.advertencias()).isEmpty();
            assertThat(resultado.estaDegradado()).isFalse();
        }

        @Test
        @DisplayName("convierte una regla degradada en advertencia, no en silencio")
        void degradadaProduceAdvertencia() {
            MotorDeReglas motor =
                    new MotorDeReglas(List.of(regla("SIN_DATOS", _ -> ResultadoDeRegla.degradada("cache caída"))));

            ResultadoDelMotor resultado = motor.ejecutar(CONTEXTO);

            assertThat(resultado.senales()).isEmpty();
            assertThat(resultado.advertencias()).singleElement().asString().contains("SIN_DATOS", "cache caída");
            assertThat(resultado.estaDegradado()).isTrue();
        }

        @Test
        @DisplayName("conserva el orden de declaración de las reglas en la evidencia")
        void conservaElOrden() {
            MotorDeReglas motor = new MotorDeReglas(
                    List.of(reglaQueDispara("PRIMERA", 10), reglaQueDispara("SEGUNDA", 900), reglaQueDispara("TERCERA", 50)));

            ResultadoDelMotor resultado = motor.ejecutar(CONTEXTO);

            assertThat(resultado.senales())
                    .extracting(SenalDeRiesgo::codigo)
                    .containsExactly("PRIMERA", "SEGUNDA", "TERCERA");
        }
    }

    @Nested
    @DisplayName("aislamiento de fallos")
    class AislamientoDeFallos {

        @Test
        @DisplayName("una regla que lanza no impide que las demás se evalúen")
        void unaReglaQueLanzaNoHundeLaEvaluacion() {
            MotorDeReglas motor = new MotorDeReglas(List.of(
                    reglaQueDispara("ANTES", 100),
                    regla("EXPLOTA", _ -> {
                        throw new IllegalStateException("índice fuera de rango");
                    }),
                    reglaQueDispara("DESPUES", 50)));

            ResultadoDelMotor resultado = motor.ejecutar(CONTEXTO);

            assertThat(resultado.senales()).extracting(SenalDeRiesgo::codigo).containsExactly("ANTES", "DESPUES");
            assertThat(resultado.advertencias())
                    .singleElement()
                    .asString()
                    .contains("EXPLOTA", "IllegalStateException", "índice fuera de rango");
        }

        @Test
        @DisplayName("una regla que devuelve null se trata como fallo, no como ausencia de evidencia")
        void reglaQueDevuelveNull() {
            MotorDeReglas motor = new MotorDeReglas(List.of(regla("NULA", _ -> null)));

            ResultadoDelMotor resultado = motor.ejecutar(CONTEXTO);

            assertThat(resultado.senales()).isEmpty();
            assertThat(resultado.advertencias()).singleElement().asString().contains("NULA", "null");
        }

        @Test
        @DisplayName("una excepción sin mensaje se reporta igualmente con su tipo")
        void excepcionSinMensaje() {
            MotorDeReglas motor = new MotorDeReglas(List.of(regla("MUDA", _ -> {
                throw new NullPointerException();
            })));

            assertThat(motor.ejecutar(CONTEXTO).advertencias())
                    .singleElement()
                    .asString()
                    .contains("MUDA", "NullPointerException");
        }
    }

    @Nested
    @DisplayName("recolección de contadores")
    class RecoleccionDeContadores {

        private static final ClaveDeVelocidad CLAVE_A =
                ClaveDeVelocidad.de(DimensionDeVelocidad.TRANSACCIONES_POR_CLIENTE, "cliente-000042");
        private static final ClaveDeVelocidad CLAVE_B =
                ClaveDeVelocidad.de(DimensionDeVelocidad.INTENTOS_POR_DISPOSITIVO, "fp-navegador-abc123");

        private static ReglaDeFraude reglaQuePide(String codigo, ClaveDeVelocidad... claves) {
            return new ReglaDeFraude() {
                @Override
                public String codigo() {
                    return codigo;
                }

                @Override
                public Set<ClaveDeVelocidad> contadoresQueNecesita(Transaccion transaccion) {
                    return Set.of(claves);
                }

                @Override
                public ResultadoDeRegla evaluar(ContextoDeEvaluacion contexto) {
                    return ResultadoDeRegla.noDisparada();
                }
            };
        }

        @Test
        @DisplayName("deduplica los contadores que piden varias reglas")
        void deduplicaLosContadores() {
            MotorDeReglas motor = new MotorDeReglas(
                    List.of(reglaQuePide("UNA", CLAVE_A), reglaQuePide("OTRA", CLAVE_A, CLAVE_B)));

            assertThat(motor.contadoresNecesarios(ObjetosDePrueba.transaccionWeb()))
                    .containsExactlyInAnyOrder(CLAVE_A, CLAVE_B);
        }

        @Test
        @DisplayName("una regla que falla al declarar no deja sin contadores a las demás")
        void aislaFalloAlDeclarar() {
            ReglaDeFraude rota = new ReglaDeFraude() {
                @Override
                public String codigo() {
                    return "ROTA";
                }

                @Override
                public Set<ClaveDeVelocidad> contadoresQueNecesita(Transaccion transaccion) {
                    throw new IllegalStateException("no debería pasar");
                }

                @Override
                public ResultadoDeRegla evaluar(ContextoDeEvaluacion contexto) {
                    return ResultadoDeRegla.noDisparada();
                }
            };

            MotorDeReglas motor = new MotorDeReglas(List.of(rota, reglaQuePide("SANA", CLAVE_B)));

            assertThat(motor.contadoresNecesarios(ObjetosDePrueba.transaccionWeb())).containsExactly(CLAVE_B);
        }

        @Test
        @DisplayName("una regla sin contadores no pide nada")
        void reglaSinContadores() {
            MotorDeReglas motor = new MotorDeReglas(List.of(reglaQueDispara("SIMPLE", 10)));

            assertThat(motor.contadoresNecesarios(ObjetosDePrueba.transaccionWeb())).isEmpty();
        }
    }
}
