package com.centinela.fraude.infraestructura.adaptador.entrada.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.centinela.fraude.infraestructura.config.PropiedadesDeSeguridad;
import jakarta.servlet.FilterChain;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * El filtro de clave de API, aislado.
 *
 * <p>Sin contexto de Spring: el filtro es una clase con dos dependencias y se instancia a mano.
 * Lo que se fija aquí es el comportamiento de seguridad, que conviene tener escrito de forma
 * que sea imposible relajarlo sin que un test se ponga rojo.
 */
@DisplayName("FiltroDeApiKey")
class FiltroDeApiKeyTest {

    private static final String CLAVE = "clave-secreta-de-prueba";
    private static final Clock RELOJ = Clock.fixed(Instant.parse("2026-08-24T12:00:00Z"), ZoneOffset.UTC);

    private FiltroDeApiKey filtro(String... rutasPublicas) {
        return new FiltroDeApiKey(
                new PropiedadesDeSeguridad(CLAVE, List.of(rutasPublicas)),
                RELOJ,
                JsonMapper.builder().build());
    }

    private static MockHttpServletRequest peticion(String ruta, String clave) {
        MockHttpServletRequest peticion = new MockHttpServletRequest("GET", ruta);
        peticion.setRequestURI(ruta);
        if (clave != null) {
            peticion.addHeader(FiltroDeApiKey.CABECERA, clave);
        }
        return peticion;
    }

    @Test
    @DisplayName("deja pasar la petición con la clave correcta")
    void claveCorrecta() throws Exception {
        MockHttpServletResponse respuesta = new MockHttpServletResponse();
        FilterChain cadena = new MockFilterChain();

        filtro().doFilter(peticion("/api/v1/evaluaciones/tx-1", CLAVE), respuesta, cadena);

        assertThat(respuesta.getStatus()).isEqualTo(200);
    }

    @ParameterizedTest(name = "clave = \"{0}\"")
    @ValueSource(strings = {"", "   ", "clave-incorrecta", "clave-secreta-de-prueb", "clave-secreta-de-pruebaX"})
    @DisplayName("rechaza cualquier clave que no sea exactamente la esperada")
    void claveInvalida(String clave) throws Exception {
        MockHttpServletResponse respuesta = new MockHttpServletResponse();

        filtro().doFilter(peticion("/api/v1/evaluaciones/tx-1", clave), respuesta, new MockFilterChain());

        assertThat(respuesta.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("rechaza la petición sin cabecera")
    void sinCabecera() throws Exception {
        MockHttpServletResponse respuesta = new MockHttpServletResponse();

        filtro().doFilter(peticion("/api/v1/evaluaciones/tx-1", null), respuesta, new MockFilterChain());

        assertThat(respuesta.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("no distingue en la respuesta entre cabecera ausente y clave incorrecta")
    void elFalloNoRevelaNada() throws Exception {
        // Dos respuestas distintas confirmarían a quien sondea que la ruta existe y que la
        // clave probada tenía el formato correcto. Tienen que ser indistinguibles.
        MockHttpServletResponse sinCabecera = new MockHttpServletResponse();
        MockHttpServletResponse claveMala = new MockHttpServletResponse();

        filtro().doFilter(peticion("/api/v1/evaluaciones/tx-1", null), sinCabecera, new MockFilterChain());
        filtro().doFilter(peticion("/api/v1/evaluaciones/tx-1", "otra"), claveMala, new MockFilterChain());

        assertThat(sinCabecera.getStatus()).isEqualTo(claveMala.getStatus());
        assertThat(sinCabecera.getContentAsString()).isEqualTo(claveMala.getContentAsString());
    }

    @Test
    @DisplayName("el 401 sale como Problem Details, igual que el resto de la API")
    void respondeProblemDetails() throws Exception {
        // El filtro corre antes del DispatcherServlet, así que el @RestControllerAdvice no lo
        // ve: si no serializara aquí, este sería el único error de la API con otra forma.
        MockHttpServletResponse respuesta = new MockHttpServletResponse();

        filtro().doFilter(peticion("/api/v1/evaluaciones/tx-1", null), respuesta, new MockFilterChain());

        assertThat(respuesta.getContentType()).contains(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(respuesta.getContentAsString())
                .contains("\"codigo\":\"NO_AUTORIZADO\"")
                .contains("\"status\":401")
                .contains("ocurridoEn");
    }

    @Test
    @DisplayName("la respuesta de error nunca contiene la clave esperada")
    void nuncaFiltraLaClave() throws Exception {
        MockHttpServletResponse respuesta = new MockHttpServletResponse();

        filtro().doFilter(peticion("/api/v1/evaluaciones/tx-1", "intento"), respuesta, new MockFilterChain());

        assertThat(respuesta.getContentAsString()).doesNotContain(CLAVE).doesNotContain("intento");
    }

    @Test
    @DisplayName("la sonda de salud queda exenta: sin ella el balanceador reciclaría las tareas")
    void laSondaDeSaludEsPublica() throws Exception {
        // Si el filtro protegiera /actuator/health, el health check del grupo de destino daría
        // 401, ECS marcaría la tarea como no sana y la reciclaría en bucle. El servicio nunca
        // llegaría a estar disponible y el motivo no se parecería a "falta una cabecera".
        var filtro = filtro("/actuator/health", "/actuator/health/**");

        for (String ruta : List.of("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness")) {
            MockHttpServletResponse respuesta = new MockHttpServletResponse();
            filtro.doFilter(peticion(ruta, null), respuesta, new MockFilterChain());
            assertThat(respuesta.getStatus()).as("ruta %s", ruta).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("lo que no esté declarado público sigue exigiendo clave, incluidas las métricas")
    void elRestoDeActuatorNoEsPublico() throws Exception {
        var filtro = filtro("/actuator/health", "/actuator/health/**");
        MockHttpServletResponse respuesta = new MockHttpServletResponse();

        filtro.doFilter(peticion("/actuator/prometheus", null), respuesta, new MockFilterChain());

        assertThat(respuesta.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("detecta que se está usando la clave de desarrollo")
    void detectaLaClaveDeDesarrollo() {
        var conDefecto = new PropiedadesDeSeguridad(PropiedadesDeSeguridad.CLAVE_DE_DESARROLLO, List.of());
        var conPropia = new PropiedadesDeSeguridad("una-clave-de-verdad", List.of());

        assertThat(conDefecto.usaClaveDeDesarrollo()).isTrue();
        assertThat(conPropia.usaClaveDeDesarrollo()).isFalse();
    }
}
