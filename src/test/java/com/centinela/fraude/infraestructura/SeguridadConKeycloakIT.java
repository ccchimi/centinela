package com.centinela.fraude.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * La autenticación de punta a punta contra un Keycloak real, con el realm del repositorio.
 *
 * <p>Los tokens se piden a Keycloak por client credentials, igual que lo haría el autorizador,
 * y viajan en la cabecera {@code Authorization}. Lo que este test prueba y el de
 * {@code @WebMvcTest} no puede: que la firma se verifica contra las claves que publica el emisor,
 * que el {@code iss} y la {@code aud} se comprueban de verdad, y que los roles salen del sitio
 * del token donde Keycloak los pone.
 *
 * <p>El {@link MockMvc} se construye a mano sobre el contexto en lugar de con
 * {@code @AutoConfigureMockMvc}: una anotación más en la clase partiría el caché de contextos y
 * esta prueba volvería a arrancar Kafka, LocalStack, Redis y Keycloak por su cuenta.
 */
@DisplayName("Seguridad con Keycloak real")
class SeguridadConKeycloakIT extends PruebaDeIntegracion {

    @Autowired
    private WebApplicationContext contexto;

    @Autowired
    private GenericContainer<?> keycloak;

    @Autowired
    private JsonMapper json;

    private MockMvc mvc;

    @BeforeEach
    void construirMockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(contexto).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("el autorizador obtiene un token y evalúa una transacción de punta a punta")
    void autorizadorEvalua() throws Exception {
        String token = token("autorizador", "secreto-autorizador-SOLO-DESARROLLO");
        String idTransaccion = "tx-" + UUID.randomUUID();

        mvc.perform(post("/api/v1/evaluaciones")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(compra(idTransaccion)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.idTransaccion").value(idTransaccion));
    }

    @Test
    @DisplayName("el autorizador no puede leer perfiles de comportamiento")
    void autorizadorNoLeePerfiles() throws Exception {
        String token = token("autorizador", "secreto-autorizador-SOLO-DESARROLLO");

        mvc.perform(get("/api/v1/perfiles/cli-1").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("ACCESO_DENEGADO"));
    }

    @Test
    @DisplayName("la herramienta de analistas lee perfiles pero no puede evaluar")
    void analistas() throws Exception {
        String token = token("herramienta-analistas", "secreto-analistas-SOLO-DESARROLLO");

        mvc.perform(get("/api/v1/perfiles/cli-sin-historial").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/evaluaciones")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(compra("tx-" + UUID.randomUUID())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("un token válido del mismo realm pero emitido para otra audiencia se rechaza, aunque tenga el rol")
    void otraAudiencia() throws Exception {
        String token = token("otro-servicio", "secreto-otro-SOLO-DESARROLLO");
        assertThat(reclamo(token, "realm_access").get("roles").toString()).contains("evaluador");
        assertThat(reclamo(token, "aud")).isNull();

        mvc.perform(post("/api/v1/evaluaciones")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(compra("tx-" + UUID.randomUUID())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("NO_AUTORIZADO"));
    }

    @Test
    @DisplayName("un token con el contenido alterado no pasa la verificación de firma")
    void tokenAlterado() throws Exception {
        String[] partes = token("herramienta-analistas", "secreto-analistas-SOLO-DESARROLLO").split("\\.");
        // Se cambia el payload por otro que se otorga el rol evaluador, conservando la firma original.
        String payload = new String(Base64.getUrlDecoder().decode(partes[1]), StandardCharsets.UTF_8)
                .replace("\"analista\"", "\"evaluador\"");
        String falsificado = partes[0] + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                + "." + partes[2];

        mvc.perform(post("/api/v1/evaluaciones")
                        .header("Authorization", "Bearer " + falsificado)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(compra("tx-" + UUID.randomUUID())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("un secreto de cliente incorrecto no obtiene token")
    void secretoIncorrecto() throws Exception {
        HttpResponse<String> respuesta = pedirToken("autorizador", "secreto-equivocado");

        assertThat(respuesta.statusCode()).isEqualTo(401);
    }

    private static String compra(String idTransaccion) {
        return TransaccionesJson.compraWeb(
                idTransaccion, "cli-" + UUID.randomUUID(), "42.00", "com-1", "5942", "ES",
                40.4168, -3.7038, "ES", "disp-1", "2026-08-24T12:00:00Z");
    }

    private String token(String cliente, String secreto) throws IOException, InterruptedException {
        HttpResponse<String> respuesta = pedirToken(cliente, secreto);
        assertThat(respuesta.statusCode()).as("token para %s: %s", cliente, respuesta.body()).isEqualTo(200);
        return json.readTree(respuesta.body()).get("access_token").asString();
    }

    private HttpResponse<String> pedirToken(String cliente, String secreto) throws IOException, InterruptedException {
        URI endpoint = URI.create("http://%s:%d/realms/centinela/protocol/openid-connect/token"
                .formatted(keycloak.getHost(), keycloak.getMappedPort(8080)));
        String formulario = "grant_type=client_credentials&client_id=%s&client_secret=%s".formatted(
                URLEncoder.encode(cliente, StandardCharsets.UTF_8), URLEncoder.encode(secreto, StandardCharsets.UTF_8));
        try (HttpClient http = HttpClient.newHttpClient()) {
            return http.send(
                    HttpRequest.newBuilder(endpoint)
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(formulario))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    private JsonNode reclamo(String token, String nombre) {
        String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
        return json.readTree(payload).get(nombre);
    }
}
