package com.centinela.fraude;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import com.centinela.fraude.infraestructura.config.PistasDeImagenNativa;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Raíz de composición del motor.
 *
 * <p>Es la única clase fuera de {@code infraestructura} que conoce Spring, y no contiene lógica:
 * arranca el contexto y nada más. Todo el cableado real vive en
 * {@code infraestructura.config}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
@ImportRuntimeHints(PistasDeImagenNativa.class)
public class CentinelaApplication {

    public static void main(String[] args) {
        SpringApplication.run(CentinelaApplication.class, args);
    }
}
