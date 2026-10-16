package com.centinela.fraude.infraestructura.adaptador.entrada.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.centinela.fraude.dominio.modelo.EvaluacionDeFraude;
import com.centinela.fraude.dominio.modelo.vo.IdTransaccion;
import com.centinela.fraude.dominio.puerto.entrada.EvaluarTransaccionUseCase;
import com.centinela.fraude.dominio.puerto.salida.ActaDeDecisiones;
import com.centinela.fraude.dominio.puerto.salida.RepositorioDePerfiles;
import com.centinela.fraude.infraestructura.config.ConfiguracionDeSeguridad;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Autenticación y reglas de acceso de la API, con la cadena de seguridad real.
 *
 * <p>Los tokens son sintéticos ({@code jwt()} de Spring Security Test): aquí se prueba qué
 * puede hacer cada rol, no la validación criptográfica del token. Esa la cubre
 * {@code SeguridadConKeycloakIT} contra un Keycloak de verdad.
 *
 * <p>Los casos de rechazo verifican además que el caso de uso <strong>no se ejecutó</strong>: un
 * 403 que llega después de evaluar la transacción seguiría siendo una fuga, aunque el código de
 * estado fuera el correcto.
 */
@WebMvcTest(
        controllers = {ControladorDeEvaluaciones.class, ControladorDePerfiles.class},
        properties = "centinela.seguridad.rutas-publicas=/actuator/health,/actuator/health/**")
@Import({
    ConfiguracionDeSeguridad.class,
    ManejadorGlobalDeErrores.class,
    ManejadorDeUltimoRecurso.class,
    ControladorDeEvaluacionesTest.PiezasReales.class
})
@DisplayName("Seguridad de la API")
class SeguridadDeLaApiTest {

    private static final String COMPRA =
            """
            {
              "idTransaccion": "tx-0001", "idCliente": "cli-0001", "importe": 120.00, "moneda": "EUR",
              "instrumento": {"tipo": "TARJETA", "bin": "454213", "ultimosCuatro": "1881", "marca": "VISA", "presente": false},
              "comercio": {"id": "com-100", "nombre": "Libreria", "mcc": "5942", "pais": "ES"},
              "canal": "WEB", "origen": {"latitud": 40.4168, "longitud": -3.7038, "pais": "ES"},
              "idDispositivo": "disp-1", "ocurridaEn": "2026-08-24T12:00:00Z"
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EvaluarTransaccionUseCase evaluador;

    @MockitoBean
    private ActaDeDecisiones acta;

    @MockitoBean
    private RepositorioDePerfiles perfiles;

    /** El decodificador real descargaría las claves del emisor; aquí se controla qué devuelve. */
    @MockitoBean
    private JwtDecoder decodificador;

    private static JwtRequestPostProcessor con(String... roles) {
        return jwt().jwt(token -> token.claim("azp", "cliente-de-prueba"))
                .authorities(List.of(roles).stream().<GrantedAuthority>map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList());
    }

    private static EvaluacionDeFraude decision() {
        return EvaluacionDeFraude.apartirDe(
                IdTransaccion.de("tx-0001"), List.of(), List.of(), Instant.parse("2026-08-24T12:00:00Z"), Duration.ZERO);
    }

    @Nested
    @DisplayName("sin token válido")
    class SinToken {

        @Test
        @DisplayName("una petición sin token devuelve 401 como Problem Detail y con WWW-Authenticate")
        void sinToken() throws Exception {
            mockMvc.perform(post("/api/v1/evaluaciones").contentType(MediaType.APPLICATION_JSON).content(COMPRA))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", Matchers.startsWith("Bearer")))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.codigo").value("NO_AUTORIZADO"))
                    .andExpect(jsonPath("$.type").value("https://centinela.example/problemas/no-autorizado"))
                    .andExpect(jsonPath("$.ocurridoEn").exists());
            verifyNoInteractions(evaluador);
        }

        @Test
        @DisplayName("un token que el decodificador rechaza devuelve el mismo 401, sin detallar el motivo en el cuerpo")
        void tokenInvalido() throws Exception {
            when(decodificador.decode(anyString())).thenThrow(new BadJwtException("Jwt expired at 2026-08-24T11:00:00Z"));

            mockMvc.perform(post("/api/v1/evaluaciones")
                            .header("Authorization", "Bearer token.vencido.o.falsificado")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(COMPRA))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", Matchers.containsString("invalid_token")))
                    .andExpect(jsonPath("$.codigo").value("NO_AUTORIZADO"))
                    .andExpect(jsonPath("$.detail").value("Falta el token de acceso o no es válido"));
            verifyNoInteractions(evaluador);
        }

        @Test
        @DisplayName("la sonda de salud no exige token")
        void sondaPublica() throws Exception {
            // 404 y no 401: la ruta pasa la seguridad; en este slice no hay actuator que la atienda.
            mockMvc.perform(get("/actuator/health")).andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("la clave compartida X-API-KEY ya no autentica")
        void apiKeyRetirada() throws Exception {
            mockMvc.perform(post("/api/v1/evaluaciones")
                            .header("X-API-KEY", "clave-de-desarrollo-no-usar-en-produccion")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(COMPRA))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("rol evaluador (autorizador)")
    class Evaluador {

        @Test
        @DisplayName("puede pedir una evaluación")
        void evalua() throws Exception {
            when(evaluador.evaluar(any())).thenReturn(decision());

            mockMvc.perform(post("/api/v1/evaluaciones")
                            .with(con("EVALUADOR"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(COMPRA))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("puede consultar una decisión")
        void consultaDecision() throws Exception {
            when(acta.buscarPor(IdTransaccion.de("tx-0001"))).thenReturn(Optional.of(decision()));

            mockMvc.perform(get("/api/v1/evaluaciones/tx-0001").with(con("EVALUADOR")))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("NO puede leer perfiles de comportamiento")
        void noLeePerfiles() throws Exception {
            mockMvc.perform(get("/api/v1/perfiles/cli-0001").with(con("EVALUADOR")))
                    .andExpect(status().isForbidden())
                    .andExpect(header().string("WWW-Authenticate", Matchers.containsString("insufficient_scope")))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.codigo").value("ACCESO_DENEGADO"));
            verifyNoInteractions(perfiles);
        }
    }

    @Nested
    @DisplayName("rol analista (herramienta de analistas)")
    class Analista {

        @Test
        @DisplayName("NO puede pedir evaluaciones")
        void noEvalua() throws Exception {
            mockMvc.perform(post("/api/v1/evaluaciones")
                            .with(con("ANALISTA"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(COMPRA))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.codigo").value("ACCESO_DENEGADO"));
            verifyNoInteractions(evaluador);
        }

        @Test
        @DisplayName("puede consultar decisiones")
        void consultaDecision() throws Exception {
            when(acta.buscarPor(IdTransaccion.de("tx-0001"))).thenReturn(Optional.of(decision()));

            mockMvc.perform(get("/api/v1/evaluaciones/tx-0001").with(con("ANALISTA")))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("puede leer perfiles: pasa la seguridad y llega al repositorio")
        void leePerfiles() throws Exception {
            when(perfiles.buscarPor(any())).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/v1/perfiles/cli-0001").with(con("ANALISTA")))
                    .andExpect(status().is(Matchers.not(Matchers.oneOf(401, 403))));
        }
    }

    @Nested
    @DisplayName("reglas generales")
    class Generales {

        @Test
        @DisplayName("un token autenticado pero sin roles no puede hacer nada")
        void sinRoles() throws Exception {
            mockMvc.perform(get("/api/v1/evaluaciones/tx-0001").with(con()))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/v1/evaluaciones").with(con()).contentType(MediaType.APPLICATION_JSON).content(COMPRA))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(evaluador, acta);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"/api/v1/admin", "/api/v2/evaluaciones", "/actuator/env", "/actuator/prometheus"})
        @DisplayName("una ruta que no está en las reglas se deniega aunque el token tenga todos los roles")
        void rutaNoDeclaradaSeDeniega(String ruta) throws Exception {
            mockMvc.perform(get(ruta).with(con("EVALUADOR", "ANALISTA")))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("un método no previsto sobre una ruta conocida se deniega")
        void metodoNoPrevisto() throws Exception {
            mockMvc.perform(delete("/api/v1/evaluaciones/tx-0001").with(con("EVALUADOR", "ANALISTA")))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(acta);
        }
    }
}
