package com.centinela.fraude.infraestructura.config;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Traduce un JWT de Keycloak a la autenticación de Spring Security.
 *
 * <p>Keycloak no pone los roles en {@code scope} —que es donde Spring los busca por defecto—
 * sino en {@code realm_access.roles}. Sin este convertidor, un token perfectamente válido
 * llegaría sin ningún rol y toda petición autenticada terminaría en 403, que es un fallo seguro
 * pero desconcertante.
 *
 * <p>Sólo se leen los roles <strong>del realm</strong>, no los de cliente
 * ({@code resource_access}): los permisos de Centinela son de negocio —quién evalúa, quién
 * consulta— y no deben depender de en qué cliente de Keycloak se configuró cada uno.
 *
 * <p>El nombre del principal es {@code azp}, el cliente al que se emitió el token: los
 * consumidores de esta API son sistemas (el autorizador y la herramienta de analistas) que se
 * autentican con client credentials, y en ese flujo {@code sub} es un UUID opaco de la cuenta de
 * servicio. Con {@code azp} el log dice "autorizador", que es lo que se busca al investigar.
 */
public final class ConvertidorDeRolesDeKeycloak implements Converter<Jwt, AbstractAuthenticationToken> {

    static final String CLAIM_REALM = "realm_access";
    static final String CLAIM_ROLES = "roles";
    static final String CLAIM_CLIENTE = "azp";

    @Override
    public AbstractAuthenticationToken convert(Jwt token) {
        String principal = token.hasClaim(CLAIM_CLIENTE) ? token.getClaimAsString(CLAIM_CLIENTE) : token.getSubject();
        return new JwtAuthenticationToken(token, roles(token), principal);
    }

    /**
     * Un claim con forma inesperada no es un error del cliente sino un token sin roles: se
     * autentica y las reglas de acceso lo dejan en 403. Lanzar aquí convertiría un problema de
     * configuración del emisor en un 500.
     */
    private static Collection<GrantedAuthority> roles(Jwt token) {
        if (!(token.getClaims().get(CLAIM_REALM) instanceof Map<?, ?> realm)
                || !(realm.get(CLAIM_ROLES) instanceof Collection<?> roles)) {
            return List.of();
        }
        return roles.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(rol -> !rol.isBlank())
                .<GrantedAuthority>map(rol -> new SimpleGrantedAuthority("ROLE_" + rol.toUpperCase(Locale.ROOT)))
                .toList();
    }
}
