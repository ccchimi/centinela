package com.centinela.fraude.infraestructura.adaptador.entrada.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.centinela.fraude.dominio.excepcion.ConflictoDeVersion;
import com.centinela.fraude.dominio.excepcion.ReglaDeNegocioIncumplida;
import com.centinela.fraude.dominio.modelo.EvaluacionDeFraude;
import com.centinela.fraude.dominio.modelo.SenalDeRiesgo;
import com.centinela.fraude.dominio.modelo.vo.IdTransaccion;
import com.centinela.fraude.dominio.puerto.entrada.EvaluarTransaccionUseCase;
import com.centinela.fraude.dominio.puerto.salida.ActaDeDecisiones;
import com.centinela.fraude.dominio.puerto.salida.RepositorioDePerfiles;
import com.centinela.fraude.infraestructura.adaptador.entrada.contrato.TraductorDeTransacciones;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * La API REST y el manejador global de errores, con MockMvc.
 *
 * <p>{@code @WebMvcTest} levanta sólo la capa web: los controladores, el
 * {@code @RestControllerAdvice} y la serialización. El caso de uso y los puertos son mocks, así
 * que este test no comprueba lógica de fraude —para eso están los unitarios y los de
 * integración— sino el contrato HTTP: códigos de estado, forma del cuerpo y, sobre todo, que
 * cada excepción de dominio salga con el estado que le corresponde.
 *
 * <p>Sin filtros: la autenticación y las reglas de acceso se prueban aparte, en
 * {@link SeguridadDeLaApiTest}. Mezclarlas aquí obligaría a adjuntar un token a cada petición y
 * cada test de contrato fallaría también por motivos de seguridad, que no es lo que mide.
 */
@WebMvcTest(controllers = {ControladorDeEvaluaciones.class, ControladorDePerfiles.class})
@AutoConfigureMockMvc(addFilters = false)
@Import({
    ManejadorGlobalDeErrores.class,
    ManejadorDeUltimoRecurso.class,
    ControladorDeEvaluacionesTest.PiezasReales.class
})
@DisplayName("API REST de evaluaciones")
class ControladorDeEvaluacionesTest {

    private static final Instant AHORA = Instant.parse("2026-08-24T12:00:00Z");

    /**
     * Lo que no se simula.
     *
     * <p>El traductor se instancia de verdad, con un validador de verdad: es el punto donde se
     * decide si una petición es evaluable, y simularlo dejaría sin probar justo la traducción
     * que produce los 400. El reloj se fija para que las respuestas sean deterministas.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class PiezasReales {

        @Bean
        Clock reloj() {
            return Clock.fixed(AHORA, ZoneOffset.UTC);
        }

        @Bean
        TraductorDeTransacciones traductorDeTransacciones() {
            try (ValidatorFactory fabrica = Validation.buildDefaultValidatorFactory()) {
                return new TraductorDeTransacciones(fabrica.getValidator());
            }
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EvaluarTransaccionUseCase evaluador;

    @MockitoBean
    private ActaDeDecisiones acta;

    @MockitoBean
    private RepositorioDePerfiles perfiles;

    private static final String COMPRA_VALIDA =
            """
            {
              "idTransaccion": "tx-0001",
              "idCliente": "cli-0001",
              "importe": 120.00,
              "moneda": "EUR",
              "instrumento": {
                "tipo": "TARJETA", "bin": "454213", "ultimosCuatro": "1881",
                "marca": "VISA", "presente": false
              },
              "comercio": { "id": "com-100", "nombre": "Libreria", "mcc": "5942", "pais": "ES" },
              "canal": "WEB",
              "origen": { "latitud": 40.4168, "longitud": -3.7038, "pais": "ES" },
              "idDispositivo": "disp-1",
              "ocurridaEn": "2026-08-24T12:00:00Z"
            }
            """;

    private static EvaluacionDeFraude decisionDePrueba() {
        return EvaluacionDeFraude.apartirDe(
                IdTransaccion.de("tx-0001"),
                List.of(SenalDeRiesgo.de("PAIS_INUSUAL", "Origen no habitual", 120)),
                List.of("RAFAGA_POR_CLIENTE: sin datos suficientes (contador no disponible)"),
                AHORA,
                Duration.ofMillis(12));
    }

    @Nested
    @DisplayName("evaluación síncrona")
    class Evaluacion {

        @Test
        @DisplayName("devuelve 201 con la decisión y la cabecera Location")
        void evaluaCorrectamente() throws Exception {
            when(evaluador.evaluar(any())).thenReturn(decisionDePrueba());

            mockMvc.perform(post("/api/v1/evaluaciones")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(COMPRA_VALIDA))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", "/api/v1/evaluaciones/tx-0001"))
                    .andExpect(jsonPath("$.idTransaccion").value("tx-0001"))
                    .andExpect(jsonPath("$.decision").value("APROBAR"))
                    .andExpect(jsonPath("$.puntaje").value(120))
                    .andExpect(jsonPath("$.degradada").value(true))
                    .andExpect(jsonPath("$.senales[0].codigo").value("PAIS_INUSUAL"))
                    .andExpect(jsonPath("$.advertencias.length()").value(1));
        }

        @Test
        @DisplayName("un cuerpo que no supera Bean Validation devuelve 400 con los campos")
        void validacionDelCuerpo() throws Exception {
            String sinCliente = COMPRA_VALIDA.replace("\"idCliente\": \"cli-0001\",", "");

            mockMvc.perform(post("/api/v1/evaluaciones")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(sinCliente))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.codigo").value("PETICION_INVALIDA"))
                    .andExpect(jsonPath("$.errores").isArray());
        }

        @Test
        @DisplayName("un instrumento desconocido devuelve 400, no 500")
        void instrumentoDesconocido() throws Exception {
            String raro = COMPRA_VALIDA.replace("\"tipo\": \"TARJETA\"", "\"tipo\": \"CHEQUE_AL_PORTADOR\"");

            mockMvc.perform(post("/api/v1/evaluaciones")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(raro))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.codigo").value("TRANSACCION_NO_EVALUABLE"))
                    .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("CHEQUE_AL_PORTADOR")));
        }
    }

    @Nested
    @DisplayName("consulta del rastro de auditoría")
    class Consulta {

        @Test
        @DisplayName("devuelve la decisión asentada")
        void devuelveLaDecision() throws Exception {
            when(acta.buscarPor(IdTransaccion.de("tx-0001"))).thenReturn(Optional.of(decisionDePrueba()));

            mockMvc.perform(get("/api/v1/evaluaciones/tx-0001"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.decision").value("APROBAR"));
        }

        @Test
        @DisplayName("una transacción sin evaluar devuelve 404 como Problem Detail")
        void noEncontrada() throws Exception {
            when(acta.buscarPor(any())).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/v1/evaluaciones/tx-inexistente"))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.codigo").value("DOMINIO_RECURSO_NO_ENCONTRADO"))
                    .andExpect(jsonPath("$.type").value("https://centinela.example/problemas/dominio-recurso-no-encontrado"))
                    .andExpect(jsonPath("$.ocurridoEn").exists());
        }

        @Test
        @DisplayName("un identificador malformado es 400, no 500")
        void identificadorInvalido() throws Exception {
            // IdTransaccion rechaza caracteres raros con ViolacionDeInvariante; el manejador
            // tiene que traducirlo a 400. Sin el switch exhaustivo esto habría sido un 500.
            mockMvc.perform(get("/api/v1/evaluaciones/tx con espacios y ñ"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.codigo").value("DOMINIO_INVARIANTE_VIOLADA"));
        }
    }

    @Nested
    @DisplayName("mapeo de la jerarquía sellada de errores")
    class MapeoDeErrores {

        @Test
        @DisplayName("una regla de negocio incumplida es 422")
        void reglaIncumplida() throws Exception {
            when(acta.buscarPor(any()))
                    .thenThrow(ReglaDeNegocioIncumplida.de("DINERO_MONEDAS_INCOMPATIBLES", "EUR contra USD"));

            mockMvc.perform(get("/api/v1/evaluaciones/tx-0001"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.codigo").value("DINERO_MONEDAS_INCOMPATIBLES"));
        }

        @Test
        @DisplayName("un conflicto de versión es 409, porque reintentar tiene sentido")
        void conflictoDeVersion() throws Exception {
            when(acta.buscarPor(any())).thenThrow(ConflictoDeVersion.de("perfil", "cli-1", 4));

            mockMvc.perform(get("/api/v1/evaluaciones/tx-0001"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.codigo").value("DOMINIO_CONFLICTO_DE_VERSION"));
        }

        @Test
        @DisplayName("un fallo inesperado es 500 y no filtra el detalle interno")
        void falloInesperado() throws Exception {
            when(acta.buscarPor(any())).thenThrow(new IllegalStateException("connection pool exhausted en shard-7"));

            mockMvc.perform(get("/api/v1/evaluaciones/tx-0001"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.codigo").value("ERROR_INTERNO"))
                    .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString("shard-7"))));
        }
    }
}
