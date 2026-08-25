package com.centinela.fraude.infraestructura.config;

import com.centinela.fraude.infraestructura.adaptador.entrada.rest.FiltroDeApiKey;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import tools.jackson.databind.json.JsonMapper;

/**
 * Registra el filtro de clave de API.
 *
 * <p>Se declara con {@link FilterRegistrationBean} y no como {@code @Component} para poder fijar
 * el orden: tiene que correr <strong>antes</strong> que cualquier otro filtro que consuma el
 * cuerpo de la petición. Si se ejecutara después, una petición sin credenciales ya habría hecho
 * trabajo —deserializar, validar— antes de rechazarse, que es exactamente el vector de un
 * ataque de agotamiento de recursos contra un endpoint público.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PropiedadesDeSeguridad.class)
public class ConfiguracionDeSeguridad {

    private static final Logger log = LoggerFactory.getLogger(ConfiguracionDeSeguridad.class);

    @Bean
    FilterRegistrationBean<FiltroDeApiKey> filtroDeApiKey(
            PropiedadesDeSeguridad propiedades, Clock reloj, JsonMapper json) {

        if (propiedades.usaClaveDeDesarrollo()) {
            // A gritos y en WARN: si esto aparece en un log de producción, la API está abierta
            // a cualquiera que haya leído el repositorio.
            log.warn(
                    "La API está usando la CLAVE DE DESARROLLO. Es pública y está en el repositorio. "
                            + "Configura la variable de entorno API_KEY antes de exponer este servicio.");
        }

        var registro = new FilterRegistrationBean<>(new FiltroDeApiKey(propiedades, reloj, json));
        registro.addUrlPatterns("/*");
        registro.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registro;
    }
}
