package com.centinela.fraude.aplicacion.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.centinela.fraude.dominio.ObjetosDePrueba;
import com.centinela.fraude.dominio.PerfilDePrueba;
import com.centinela.fraude.dominio.excepcion.ConflictoDeVersion;
import com.centinela.fraude.dominio.modelo.Decision;
import com.centinela.fraude.dominio.modelo.EvaluacionDeFraude;
import com.centinela.fraude.dominio.modelo.PerfilDeComportamiento;
import com.centinela.fraude.dominio.modelo.SenalDeRiesgo;
import com.centinela.fraude.dominio.modelo.Transaccion;
import com.centinela.fraude.dominio.modelo.vo.ClaveDeVelocidad;
import com.centinela.fraude.dominio.modelo.vo.DimensionDeVelocidad;
import com.centinela.fraude.dominio.modelo.vo.IdCliente;
import com.centinela.fraude.dominio.puerto.salida.ActaDeDecisiones;
import com.centinela.fraude.dominio.puerto.salida.ContadorDeVelocidad;
import com.centinela.fraude.dominio.puerto.salida.RepositorioDePerfiles;
import com.centinela.fraude.dominio.regla.ContextoDeEvaluacion;
import com.centinela.fraude.dominio.regla.MotorDeReglas;
import com.centinela.fraude.dominio.regla.PoliticaDeRiesgo;
import com.centinela.fraude.dominio.regla.ReglaDeFraude;
import com.centinela.fraude.dominio.regla.ResultadoDeRegla;
import com.centinela.fraude.dominio.regla.catalogo.CatalogoDeReglas;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Pruebas del caso de uso. Solo JUnit y Mockito: sin contexto de Spring, sin contenedores.
 *
 * <p>Lo que estos tests fijan no es el cálculo del riesgo —de eso se ocupan los tests de las
 * reglas— sino el <strong>comportamiento bajo fallo</strong> y el <strong>ciclo de
 * aprendizaje</strong>: qué pasa cuando el perfil no llega, cuando la caché no responde, y bajo
 * qué condiciones el motor puede escribir de vuelta lo que ha aprendido.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("EvaluadorDeFraudeService")
class EvaluadorDeFraudeServiceTest {

    private static final Clock RELOJ = Clock.fixed(ObjetosDePrueba.AHORA, ZoneOffset.UTC);

    private static final ClaveDeVelocidad CLAVE =
            ClaveDeVelocidad.de(DimensionDeVelocidad.TRANSACCIONES_POR_CLIENTE, ObjetosDePrueba.cliente());

    @Mock
    private RepositorioDePerfiles perfiles;

    @Mock
    private ContadorDeVelocidad contadores;

    @Mock
    private ActaDeDecisiones acta;

    private EvaluadorDeFraudeService servicio;

    @AfterEach
    void cerrar() {
        if (servicio != null) {
            servicio.close();
        }
    }

    // ---------------------------------------------------------------------------------
    // Utilidades
    // ---------------------------------------------------------------------------------

    private EvaluadorDeFraudeService construir(MotorDeReglas motor) {
        return construir(motor, ConfiguracionDelEvaluador.porDefecto());
    }

    private EvaluadorDeFraudeService construir(MotorDeReglas motor, ConfiguracionDelEvaluador configuracion) {
        servicio = new EvaluadorDeFraudeService(motor, perfiles, contadores, acta, configuracion, RELOJ);
        return servicio;
    }

    /** Regla de laboratorio que dispara siempre con el aporte indicado. */
    private static ReglaDeFraude reglaQueAporta(int aporte) {
        return new ReglaDeFraude() {
            @Override
            public String codigo() {
                return "REGLA_DE_PRUEBA";
            }

            @Override
            public ResultadoDeRegla evaluar(ContextoDeEvaluacion contexto) {
                return ResultadoDeRegla.disparada(codigo(), "evidencia de prueba", aporte);
            }
        };
    }

    /** Regla que no decide nada pero deja ver el contexto que le llegó. */
    private static ReglaDeFraude reglaEspia(AtomicReference<ContextoDeEvaluacion> capturado, ClaveDeVelocidad clave) {
        return new ReglaDeFraude() {
            @Override
            public String codigo() {
                return "ESPIA";
            }

            @Override
            public Set<ClaveDeVelocidad> contadoresQueNecesita(Transaccion transaccion) {
                return clave == null ? Set.of() : Set.of(clave);
            }

            @Override
            public ResultadoDeRegla evaluar(ContextoDeEvaluacion contexto) {
                capturado.set(contexto);
                return ResultadoDeRegla.noDisparada();
            }
        };
    }

    // ---------------------------------------------------------------------------------

    @Nested
    @DisplayName("camino normal")
    class CaminoNormal {

        @Test
        @DisplayName("devuelve la decisión derivada de las señales del motor")
        void devuelveLaDecisionDelMotor() {
            when(perfiles.buscarPor(any())).thenReturn(Optional.of(PerfilDePrueba.unPerfil().construir()));

            EvaluacionDeFraude evaluacion =
                    construir(new MotorDeReglas(List.of(reglaQueAporta(900)))).evaluar(ObjetosDePrueba.transaccionWeb());

            assertThat(evaluacion.puntaje().valor()).isEqualTo(900);
            assertThat(evaluacion.decision()).isEqualTo(Decision.RECHAZAR);
            assertThat(evaluacion.senales()).extracting(SenalDeRiesgo::codigo).containsExactly("REGLA_DE_PRUEBA");
            assertThat(evaluacion.estaDegradada()).isFalse();
        }

        @Test
        @DisplayName("fecha la evaluación con el reloj inyectado, no con el del sistema")
        void usaElRelojInyectado() {
            when(perfiles.buscarPor(any())).thenReturn(Optional.of(PerfilDePrueba.unPerfil().construir()));

            EvaluacionDeFraude evaluacion =
                    construir(new MotorDeReglas(List.of(reglaQueAporta(10)))).evaluar(ObjetosDePrueba.transaccionWeb());

            assertThat(evaluacion.evaluadaEn()).isEqualTo(ObjetosDePrueba.AHORA);
            // La latencia, en cambio, se mide con nanoTime: sigue siendo real aunque el reloj
            // esté congelado, que es justo lo que permite medirla en un test determinista.
            assertThat(evaluacion.latencia()).isGreaterThanOrEqualTo(Duration.ZERO);
        }

        @Test
        @DisplayName("asienta la decisión una sola vez y no toca Kafka")
        void asientaLaDecision() {
            when(perfiles.buscarPor(any())).thenReturn(Optional.of(PerfilDePrueba.unPerfil().construir()));

            EvaluacionDeFraude evaluacion =
                    construir(new MotorDeReglas(List.of(reglaQueAporta(10)))).evaluar(ObjetosDePrueba.transaccionWeb());

            // El puerto de publicación ya ni siquiera es dependencia de este servicio: la
            // decisión se asienta con su obligación de publicarse y el relevo la cumple aparte.
            verify(acta).asentar(evaluacion);
        }

        @Test
        @DisplayName("incrementa los contadores que las reglas declararon, con la ventana de la política")
        void incrementaLosContadoresDeclarados() {
            when(perfiles.buscarPor(any())).thenReturn(Optional.of(PerfilDePrueba.unPerfil().construir()));
            when(contadores.incrementarYContar(any(), any())).thenReturn(7L);
            AtomicReference<ContextoDeEvaluacion> capturado = new AtomicReference<>();

            construir(new MotorDeReglas(List.of(reglaEspia(capturado, CLAVE))))
                    .evaluar(ObjetosDePrueba.transaccionWeb());

            verify(contadores).incrementarYContar(CLAVE, Duration.ofSeconds(60));
            assertThat(capturado.get().contador(CLAVE)).hasValue(7L);
        }

        @Test
        @DisplayName("no toca la caché de velocidad si ninguna regla pide contadores")
        void noPideContadoresInnecesarios() {
            when(perfiles.buscarPor(any())).thenReturn(Optional.of(PerfilDePrueba.unPerfil().construir()));

            construir(new MotorDeReglas(List.of(reglaQueAporta(10)))).evaluar(ObjetosDePrueba.transaccionWeb());

            verify(contadores, never()).incrementarYContar(any(), any());
        }
    }

    @Nested
    @DisplayName("realimentación del perfil")
    class RealimentacionDelPerfil {

        @Test
        @DisplayName("una aprobación incorpora la transacción y sube la versión")
        void aprendeDeUnaAprobacion() {
            PerfilDeComportamiento base =
                    PerfilDePrueba.unPerfil().conMedia("100.00").enVersion(7).construir();
            when(perfiles.buscarPor(any())).thenReturn(Optional.of(base));

            construir(new MotorDeReglas(List.of(reglaQueAporta(10)))).evaluar(ObjetosDePrueba.transaccionWeb());

            ArgumentCaptor<PerfilDeComportamiento> guardado = ArgumentCaptor.forClass(PerfilDeComportamiento.class);
            verify(perfiles).guardar(guardado.capture());

            assertThat(guardado.getValue().version()).isEqualTo(8);
            assertThat(guardado.getValue().transaccionesObservadas())
                    .isEqualTo(base.transaccionesObservadas() + 1);
            assertThat(guardado.getValue().ultimaActividad()).contains(ObjetosDePrueba.AHORA);
        }

        @Test
        @DisplayName("una decisión con fricción NO enseña nada al perfil")
        void noAprendeDeLoQueSospecho() {
            // Es la guarda que impide que un atacante entrene el perfil a su favor: si una
            // compra rechazada desde Tokio hiciera habitual a Japón, la siguiente ya no
            // dispararía la regla de país inusual.
            when(perfiles.buscarPor(any())).thenReturn(Optional.of(PerfilDePrueba.unPerfil().construir()));

            construir(new MotorDeReglas(List.of(reglaQueAporta(900)))).evaluar(ObjetosDePrueba.transaccionWeb());

            verify(perfiles, never()).guardar(any());
        }

        @Test
        @DisplayName("no escribe si la lectura del perfil no fue concluyente")
        void noEscribeSobreUnaLecturaFallida() {
            // El fallo más caro posible: con el perfil provisional en mano, escribir de vuelta
            // sustituiría el historial real de un cliente de años por uno en blanco.
            when(perfiles.buscarPor(any())).thenThrow(new IllegalStateException("DynamoDB no responde"));

            EvaluacionDeFraude evaluacion =
                    construir(new MotorDeReglas(List.of(reglaQueAporta(10)))).evaluar(ObjetosDePrueba.transaccionWeb());

            assertThat(evaluacion.estaDegradada()).isTrue();
            verify(perfiles, never()).guardar(any());
        }

        @Test
        @DisplayName("un cliente nuevo sí genera perfil: la ausencia es una lectura concluyente")
        void aprendeDeUnClienteNuevo() {
            when(perfiles.buscarPor(any())).thenReturn(Optional.empty());

            construir(new MotorDeReglas(List.of(reglaQueAporta(10)))).evaluar(ObjetosDePrueba.transaccionWeb());

            ArgumentCaptor<PerfilDeComportamiento> guardado = ArgumentCaptor.forClass(PerfilDeComportamiento.class);
            verify(perfiles).guardar(guardado.capture());
            assertThat(guardado.getValue().transaccionesObservadas()).isEqualTo(1);
            assertThat(guardado.getValue().version()).isEqualTo(1);
        }

        @Test
        @DisplayName("ante un conflicto de versión relee y reintenta una vez")
        void reintentaTrasUnConflicto() {
            PerfilDeComportamiento base = PerfilDePrueba.unPerfil().enVersion(7).construir();
            PerfilDeComportamiento masReciente = PerfilDePrueba.unPerfil().enVersion(9).construir();
            when(perfiles.buscarPor(any())).thenReturn(Optional.of(base)).thenReturn(Optional.of(masReciente));
            doThrow(ConflictoDeVersion.de("perfil", "x", 7))
                    .doNothing()
                    .when(perfiles)
                    .guardar(any());

            construir(new MotorDeReglas(List.of(reglaQueAporta(10)))).evaluar(ObjetosDePrueba.transaccionWeb());

            ArgumentCaptor<PerfilDeComportamiento> guardado = ArgumentCaptor.forClass(PerfilDeComportamiento.class);
            verify(perfiles, org.mockito.Mockito.times(2)).guardar(guardado.capture());
            // El reintento parte de la versión que ganó la carrera, no de la que ya está obsoleta.
            assertThat(guardado.getAllValues().getFirst().version()).isEqualTo(8);
            assertThat(guardado.getAllValues().getLast().version()).isEqualTo(10);
        }

        @Test
        @DisplayName("un fallo al guardar el perfil no invalida la decisión")
        void elFalloAlGuardarNoRompeNada() {
            when(perfiles.buscarPor(any())).thenReturn(Optional.of(PerfilDePrueba.unPerfil().construir()));
            doThrow(new IllegalStateException("tabla no disponible")).when(perfiles).guardar(any());

            EvaluacionDeFraude evaluacion =
                    construir(new MotorDeReglas(List.of(reglaQueAporta(10)))).evaluar(ObjetosDePrueba.transaccionWeb());

            assertThat(evaluacion.decision()).isEqualTo(Decision.APROBAR);
            verify(acta).asentar(evaluacion);
        }
    }

    @Nested
    @DisplayName("cliente sin historial")
    class ClienteSinHistorial {

        @Test
        @DisplayName("evalúa con un perfil vacío y no lo trata como degradación")
        void perfilAusenteNoEsDegradacion() {
            // Un cliente nuevo es un caso de negocio normal, no una anomalía de infraestructura.
            when(perfiles.buscarPor(any())).thenReturn(Optional.empty());
            AtomicReference<ContextoDeEvaluacion> capturado = new AtomicReference<>();

            EvaluacionDeFraude evaluacion = construir(new MotorDeReglas(List.of(reglaEspia(capturado, null))))
                    .evaluar(ObjetosDePrueba.transaccionWeb());

            assertThat(capturado.get().perfil().transaccionesObservadas()).isZero();
            assertThat(capturado.get().perfil().esFiable()).isFalse();
            assertThat(evaluacion.advertencias()).isEmpty();
        }

        @Test
        @DisplayName("inicializa el perfil vacío con la moneda configurada")
        void usaLaMonedaConfigurada() {
            when(perfiles.buscarPor(any())).thenReturn(Optional.empty());
            AtomicReference<ContextoDeEvaluacion> capturado = new AtomicReference<>();
            var configuracion = new ConfiguracionDelEvaluador(
                    PoliticaDeRiesgo.porDefecto(), Duration.ofMillis(150), Currency.getInstance("USD"));

            construir(new MotorDeReglas(List.of(reglaEspia(capturado, null))), configuracion)
                    .evaluar(ObjetosDePrueba.transaccionWeb());

            assertThat(capturado.get().perfil().importeMedio().moneda().getCurrencyCode())
                    .isEqualTo("USD");
        }

        @Test
        @DisplayName("descarta un perfil que pertenece a otro cliente")
        void descartaPerfilDeOtroCliente() {
            // Un error de clave en el adaptador evaluaría al titular contra la normalidad de un
            // tercero. Es preferible evaluar a ciegas.
            PerfilDeComportamiento ajeno =
                    PerfilDePrueba.unPerfil().de(IdCliente.de("cliente-999999")).construir();
            when(perfiles.buscarPor(any())).thenReturn(Optional.of(ajeno));
            AtomicReference<ContextoDeEvaluacion> capturado = new AtomicReference<>();

            construir(new MotorDeReglas(List.of(reglaEspia(capturado, null))))
                    .evaluar(ObjetosDePrueba.transaccionWeb());

            assertThat(capturado.get().perfil().cliente()).isEqualTo(ObjetosDePrueba.cliente());
            assertThat(capturado.get().perfil().transaccionesObservadas()).isZero();
        }
    }

    @Nested
    @DisplayName("degradación")
    class Degradacion {

        @Test
        @DisplayName("si el repositorio de perfiles falla, evalúa igual y lo hace constar")
        void perfilQueFalla() {
            when(perfiles.buscarPor(any())).thenThrow(new IllegalStateException("DynamoDB no responde"));

            EvaluacionDeFraude evaluacion =
                    construir(new MotorDeReglas(List.of(reglaQueAporta(100)))).evaluar(ObjetosDePrueba.transaccionWeb());

            assertThat(evaluacion.decision()).isNotNull();
            assertThat(evaluacion.estaDegradada()).isTrue();
            assertThat(evaluacion.advertencias())
                    .singleElement()
                    .asString()
                    .contains("perfil de comportamiento", "DynamoDB no responde");
        }

        @Test
        @DisplayName("si un contador falla, la regla que lo usaba se degrada y las demás siguen")
        void contadorQueFalla() {
            when(perfiles.buscarPor(any())).thenReturn(Optional.of(PerfilDePrueba.unPerfil().construir()));
            when(contadores.incrementarYContar(any(), any())).thenThrow(new IllegalStateException("Redis caído"));
            AtomicReference<ContextoDeEvaluacion> capturado = new AtomicReference<>();

            construir(new MotorDeReglas(List.of(reglaEspia(capturado, CLAVE))))
                    .evaluar(ObjetosDePrueba.transaccionWeb());

            // Clave ausente del mapa: la regla verá "no disponible", nunca "cero".
            assertThat(capturado.get().contador(CLAVE)).isEmpty();
        }

        @Test
        @DisplayName("si el perfil no llega dentro del presupuesto, decide sin él")
        void presupuestoDeLatenciaAgotado() {
            when(perfiles.buscarPor(any())).thenAnswer(invocacion -> {
                Thread.sleep(2000);
                return Optional.empty();
            });
            var configuracionAjustada = new ConfiguracionDelEvaluador(
                    PoliticaDeRiesgo.porDefecto(), Duration.ofMillis(50), Currency.getInstance("EUR"));

            long inicio = System.nanoTime();
            EvaluacionDeFraude evaluacion = construir(new MotorDeReglas(List.of(reglaQueAporta(100))), configuracionAjustada)
                    .evaluar(ObjetosDePrueba.transaccionWeb());
            Duration transcurrido = Duration.ofNanos(System.nanoTime() - inicio);

            assertThat(evaluacion.advertencias()).singleElement().asString().contains("presupuesto", "50 ms");
            assertThat(transcurrido)
                    .as("el presupuesto debe cortar la espera, no esperar a la fuente lenta")
                    .isLessThan(Duration.ofSeconds(1));
        }

        @Test
        @DisplayName("las reglas degradadas del motor llegan a la evaluación como advertencias")
        void lasAdvertenciasDelMotorSePropagan() {
            when(perfiles.buscarPor(any())).thenReturn(Optional.of(PerfilDePrueba.unPerfil().construir()));
            // Caché de velocidad caída: hay que hacerla fallar explícitamente. El valor por
            // defecto de un mock para un long es 0, y eso NO reproduce el escenario: sería un
            // contador que responde "cero eventos", justo lo contrario de lo que se prueba.
            when(contadores.incrementarYContar(any(), any())).thenThrow(new IllegalStateException("Redis caído"));

            EvaluacionDeFraude evaluacion = construir(new MotorDeReglas(CatalogoDeReglas.estandar()))
                    .evaluar(ObjetosDePrueba.transaccionWeb());

            // Las dos reglas de ráfaga se declaran degradadas; el resto del catálogo decide.
            assertThat(evaluacion.advertencias()).hasSize(2).allSatisfy(a -> assertThat(a).contains("RAFAGA"));
            assertThat(evaluacion.estaDegradada()).isTrue();
        }

        @Test
        @DisplayName("un fallo al asentar no invalida una decisión ya tomada")
        void falloAlAsentar() {
            when(perfiles.buscarPor(any())).thenReturn(Optional.of(PerfilDePrueba.unPerfil().construir()));
            doThrow(new IllegalStateException("tabla no disponible")).when(acta).asentar(any());

            EvaluacionDeFraude evaluacion =
                    construir(new MotorDeReglas(List.of(reglaQueAporta(900)))).evaluar(ObjetosDePrueba.transaccionWeb());

            assertThat(evaluacion.decision()).isEqualTo(Decision.RECHAZAR);
        }
    }
}
