package com.centinela.fraude.infraestructura.config;

import com.centinela.fraude.aplicacion.servicio.ConfiguracionDelEvaluador;
import com.centinela.fraude.aplicacion.servicio.EvaluadorDeFraudeService;
import com.centinela.fraude.dominio.puerto.entrada.EvaluarTransaccionUseCase;
import com.centinela.fraude.dominio.puerto.salida.ContadorDeVelocidad;
import com.centinela.fraude.dominio.puerto.salida.PublicadorDeDecisiones;
import com.centinela.fraude.dominio.puerto.salida.RegistroDeAuditoria;
import com.centinela.fraude.dominio.puerto.salida.RepositorioDePerfiles;
import com.centinela.fraude.dominio.regla.MotorDeReglas;
import com.centinela.fraude.dominio.regla.catalogo.CatalogoDeReglas;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Raíz de composición del hexágono: aquí se enchufan los adaptadores al núcleo.
 *
 * <p>Es el punto que quedaba pendiente al cerrar la iteración 2. Todo el dominio y toda la capa
 * de aplicación se escribieron sin una sola anotación de framework, y el precio de esa pureza es
 * exactamente este fichero: alguien tiene que construir los objetos. Que ese alguien sea una
 * clase de infraestructura, y no una anotación repartida por el núcleo, es la diferencia entre
 * poder probar el motor sin Spring y no poder.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({PropiedadesDelMotor.class, PropiedadesDeKafka.class})
public class ConfiguracionDelHexagono {

    /**
     * Reloj inyectado, no {@code Instant.now()} disperso por el código. Es lo que permite que el
     * caso de uso se pruebe con un instante fijo y de forma determinista.
     */
    @Bean
    Clock reloj() {
        return Clock.systemUTC();
    }

    /**
     * El catálogo es una decisión de negocio y vive en el dominio; aquí sólo se envuelve. Si en
     * el futuro las reglas se activan por configuración, el cambio es filtrar esta lista, no
     * repartir {@code @Component} por el paquete de reglas.
     */
    @Bean
    MotorDeReglas motorDeReglas() {
        return new MotorDeReglas(CatalogoDeReglas.estandar());
    }

    @Bean
    ConfiguracionDelEvaluador configuracionDelEvaluador(PropiedadesDelMotor propiedades) {
        return propiedades.aConfiguracionDelEvaluador();
    }

    /**
     * El servicio implementa {@code AutoCloseable} para liberar su ejecutor de hilos virtuales;
     * se declara el {@code destroyMethod} de forma explícita para que quede escrito que el ciclo
     * de vida importa y no dependa de una convención.
     */
    @Bean(destroyMethod = "close")
    EvaluarTransaccionUseCase evaluarTransaccion(
            MotorDeReglas motor,
            RepositorioDePerfiles perfiles,
            ContadorDeVelocidad contadores,
            RegistroDeAuditoria auditoria,
            PublicadorDeDecisiones publicador,
            ConfiguracionDelEvaluador configuracion,
            Clock reloj) {
        return new EvaluadorDeFraudeService(
                motor, perfiles, contadores, auditoria, publicador, configuracion, reloj);
    }
}
