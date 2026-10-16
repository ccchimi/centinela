package com.centinela.fraude.infraestructura.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

@DisplayName("Convertidor de roles de Keycloak")
class ConvertidorDeRolesDeKeycloakTest {

    private final ConvertidorDeRolesDeKeycloak convertidor = new ConvertidorDeRolesDeKeycloak();

    private static Jwt token(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("3f2c9a1e-uuid-de-la-cuenta-de-servicio")
                .issuedAt(Instant.parse("2026-08-24T12:00:00Z"))
                .expiresAt(Instant.parse("2026-08-24T12:05:00Z"));
        claims.forEach(builder::claim);
        return builder.build();
    }

    private static List<String> autoridades(AbstractAuthenticationToken autenticacion) {
        return autenticacion.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    }

    @Test
    @DisplayName("convierte los roles del realm en ROLE_ en mayúsculas")
    void rolesDelRealm() {
        var autenticacion = convertidor.convert(token(Map.of(
                "azp", "autorizador",
                "realm_access", Map.of("roles", List.of("evaluador", "default-roles-centinela")))));

        assertThat(autoridades(autenticacion)).containsExactlyInAnyOrder("ROLE_EVALUADOR", "ROLE_DEFAULT-ROLES-CENTINELA");
    }

    @Test
    @DisplayName("el principal es el cliente (azp), no el UUID opaco de la cuenta de servicio")
    void principalEsElCliente() {
        var autenticacion = convertidor.convert(token(Map.of("azp", "herramienta-analistas")));

        assertThat(autenticacion.getName()).isEqualTo("herramienta-analistas");
    }

    @Test
    @DisplayName("sin azp, el principal es el sub")
    void sinAzpUsaSub() {
        var autenticacion = convertidor.convert(token(Map.of("realm_access", Map.of("roles", List.of("analista")))));

        assertThat(autenticacion.getName()).isEqualTo("3f2c9a1e-uuid-de-la-cuenta-de-servicio");
    }

    @Test
    @DisplayName("ignora los roles de cliente: los permisos de Centinela son del realm")
    void ignoraRolesDeCliente() {
        var autenticacion = convertidor.convert(token(Map.of(
                "resource_access", Map.of("centinela-api", Map.of("roles", List.of("evaluador"))))));

        assertThat(autenticacion.getAuthorities()).isEmpty();
    }

    @Test
    @DisplayName("un claim con forma inesperada deja el token sin roles en lugar de fallar con 500")
    void claimMalformado() {
        assertThat(convertidor.convert(token(Map.of("realm_access", "evaluador"))).getAuthorities()).isEmpty();
        assertThat(convertidor.convert(token(Map.of("realm_access", Map.of("roles", "evaluador")))).getAuthorities())
                .isEmpty();
        assertThat(convertidor.convert(token(Map.of("realm_access", Map.of("roles", Arrays.asList("analista", 42, " ", null))))))
                .extracting(ConvertidorDeRolesDeKeycloakTest::autoridades)
                .isEqualTo(List.of("ROLE_ANALISTA"));
    }
}
