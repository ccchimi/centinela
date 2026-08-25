package com.centinela.fraude.infraestructura.config;

import com.centinela.fraude.dominio.puerto.salida.ContadorDeVelocidad;
import com.centinela.fraude.dominio.puerto.salida.PublicadorDeDecisiones;
import com.centinela.fraude.dominio.puerto.salida.RegistroDeAuditoria;
import com.centinela.fraude.dominio.puerto.salida.RepositorioDePerfiles;
import com.centinela.fraude.infraestructura.adaptador.salida.dynamodb.RegistroDeAuditoriaDynamoDb;
import com.centinela.fraude.infraestructura.adaptador.salida.dynamodb.RepositorioDePerfilesDynamoDb;
import com.centinela.fraude.infraestructura.adaptador.salida.kafka.PublicadorDeDecisionesKafka;
import com.centinela.fraude.infraestructura.adaptador.salida.redis.ContadorDeVelocidadRedis;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

/**
 * Instancia los adaptadores de salida y los expone bajo el tipo del puerto.
 *
 * <p>Nótese que los adaptadores no llevan {@code @Component}: se construyen aquí, a mano. Así el
 * tipo que el resto del contexto ve es el puerto y no la implementación, y nadie puede inyectar
 * por accidente {@code RepositorioDePerfilesDynamoDb} y acabar dependiendo de DynamoDB desde un
 * sitio que no debería saber que existe.
 */
@Configuration(proxyBeanMethods = false)
public class ConfiguracionDeAdaptadoresDeSalida {

    @Bean
    RepositorioDePerfiles repositorioDePerfiles(DynamoDbAsyncClient cliente, PropiedadesDeAws propiedades) {
        return new RepositorioDePerfilesDynamoDb(cliente, propiedades.dynamodb().tablaPerfiles());
    }

    @Bean
    RegistroDeAuditoria registroDeAuditoria(DynamoDbAsyncClient cliente, PropiedadesDeAws propiedades) {
        return new RegistroDeAuditoriaDynamoDb(
                cliente,
                propiedades.dynamodb().tablaAuditoria(),
                Duration.ofDays(propiedades.dynamodb().retencionAuditoriaDias()));
    }

    @Bean
    ContadorDeVelocidad contadorDeVelocidad(StringRedisTemplate plantilla) {
        return new ContadorDeVelocidadRedis(plantilla);
    }

    @Bean
    PublicadorDeDecisiones publicadorDeDecisiones(
            KafkaTemplate<String, Object> plantilla, PropiedadesDeKafka kafka, PropiedadesDelMotor motor) {
        return new PublicadorDeDecisionesKafka(
                plantilla,
                kafka.topics().decisiones(),
                kafka.reintentos().timeoutDePublicacion(),
                motor.politica().version());
    }
}
