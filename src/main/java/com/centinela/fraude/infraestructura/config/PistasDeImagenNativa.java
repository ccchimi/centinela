package com.centinela.fraude.infraestructura.config;

import com.centinela.fraude.dominio.excepcion.ConflictoDeVersion;
import com.centinela.fraude.dominio.excepcion.RecursoDeDominioNoEncontrado;
import com.centinela.fraude.dominio.excepcion.ReglaDeNegocioIncumplida;
import com.centinela.fraude.dominio.excepcion.ViolacionDeInvariante;
import com.centinela.fraude.infraestructura.adaptador.entrada.contrato.EventoInvalidoException;
import com.centinela.fraude.infraestructura.adaptador.entrada.contrato.TransaccionEvento;
import com.centinela.fraude.infraestructura.adaptador.entrada.rest.DecisionRespuesta;
import com.centinela.fraude.infraestructura.adaptador.entrada.rest.PerfilRespuesta;
import com.centinela.fraude.infraestructura.adaptador.salida.kafka.DecisionEvento;
import java.util.List;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Pistas de accesibilidad para la compilación nativa.
 *
 * <h2>Qué necesita registrarse y por qué</h2>
 *
 * El analizador de GraalVM recorre el código de forma estática: todo lo que se alcanza por
 * llamada directa lo encuentra solo. Lo que no puede ver es lo que se resuelve por nombre en
 * tiempo de ejecución, y en este sistema eso es exactamente una cosa: <strong>los DTO de
 * cable</strong>.
 *
 * <ul>
 *   <li>{@code spring.json.value.default.type} nombra {@link TransaccionEvento} como una cadena
 *       en el YAML. El deserializador de Kafka hace {@code Class.forName} con ella; sin registro,
 *       el tipo no está en la imagen y el consumidor muere al primer mensaje.
 *   <li>Jackson accede a los componentes de los records por reflexión. Vale tanto para lo que
 *       entra ({@link TransaccionEvento}) como para lo que sale ({@link DecisionEvento} y las
 *       respuestas REST).
 * </ul>
 *
 * <h2>Qué NO hace falta registrar: el motor de reglas</h2>
 *
 * Ni una pista. Las nueve reglas se instancian con {@code new} en
 * {@code CatalogoDeReglas.estandar()}, el motor las recibe por constructor y el caso de uso se
 * cablea con {@code @Bean} explícitos. No hay escaneo de componentes en el núcleo, ni
 * {@code ServiceLoader}, ni proxies.
 *
 * <p>Es una consecuencia directa de haber mantenido el dominio y la capa de aplicación libres de
 * anotaciones, y aquí es donde se cobra: el analizador estático ve todo el motor de fraude sin
 * ayuda. Un diseño equivalente con {@code @Component} por regla habría necesitado registrar cada
 * una, y cada regla nueva sería una pista nueva que alguien olvidaría.
 *
 * <h2>Lo que aportan las librerías</h2>
 *
 * El SDK de AWS y Lettuce traen su propia metadata en {@code META-INF/native-image}.
 * {@code kafka-clients} no la trae: la aporta el repositorio de metadata de GraalVM, habilitado
 * en el perfil {@code native} del POM.
 */
public class PistasDeImagenNativa implements RuntimeHintsRegistrar {

    /** Todo lo que cruza la frontera serializado, en las dos direcciones. */
    private static final List<Class<?>> TIPOS_DE_CABLE = List.of(
            TransaccionEvento.class,
            TransaccionEvento.InstrumentoEvento.class,
            TransaccionEvento.ComercioEvento.class,
            TransaccionEvento.GeolocalizacionEvento.class,
            DecisionEvento.class,
            DecisionEvento.SenalEvento.class,
            DecisionRespuesta.class,
            DecisionRespuesta.SenalRespuesta.class,
            PerfilRespuesta.class);

    /**
     * Excepciones que el manejador global recorre con un {@code switch} sobre patrones. El
     * analizador las alcanza por el código, pero se registran para que el mecanismo de
     * serialización de Java pueda reconstruirlas si alguna vez cruzan un límite de proceso.
     */
    private static final List<Class<?>> ERRORES = List.of(
            ViolacionDeInvariante.class,
            ReglaDeNegocioIncumplida.class,
            RecursoDeDominioNoEncontrado.class,
            ConflictoDeVersion.class,
            EventoInvalidoException.class);

    /**
     * Propiedades tipadas, incluidos los records <strong>anidados</strong>.
     *
     * <p>Spring AOT registra el record externo para el enlace, pero Bean Validation lee los
     * campos por reflexión al evaluar {@code @NotBlank} y compañía, y los anidados se quedan
     * fuera. El síntoma es un {@code MissingReflectionRegistrationError} sobre un campo
     * concreto —{@code PropiedadesDeAws$DynamoDb.tablaBandeja} fue el primero— en el arranque
     * del binario, no en la compilación: no hay forma de que el build lo avise.
     */
    private static final List<Class<?>> PROPIEDADES = List.of(
            PropiedadesDeAws.class,
            PropiedadesDeAws.DynamoDb.class,
            PropiedadesDeAws.Cliente.class,
            PropiedadesDelMotor.class,
            PropiedadesDelMotor.Politica.class,
            PropiedadesDelMotor.Bandeja.class,
            PropiedadesDeKafka.class,
            PropiedadesDeKafka.Topics.class,
            PropiedadesDeKafka.Reintentos.class);

    /** Nombres de clase que sólo aparecen como cadenas en {@code application.yml}. */
    private static final List<String> SERIALIZADORES_POR_NOMBRE = List.of(
            "org.springframework.kafka.support.serializer.ErrorHandlingDeserializer",
            "org.springframework.kafka.support.serializer.JacksonJsonDeserializer",
            "org.springframework.kafka.support.serializer.JacksonJsonSerializer",
            "org.apache.kafka.common.serialization.StringSerializer",
            "org.apache.kafka.common.serialization.StringDeserializer");

    @Override
    public void registerHints(RuntimeHints pistas, ClassLoader cargador) {
        TIPOS_DE_CABLE.forEach(tipo -> pistas.reflection()
                .registerType(
                        tipo,
                        MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                        MemberCategory.INVOKE_PUBLIC_METHODS,
                        MemberCategory.ACCESS_DECLARED_FIELDS));

        PROPIEDADES.forEach(tipo -> pistas.reflection()
                .registerType(
                        tipo,
                        MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                        MemberCategory.INVOKE_PUBLIC_METHODS,
                        MemberCategory.ACCESS_DECLARED_FIELDS));

        ERRORES.forEach(tipo -> pistas.reflection().registerType(tipo, MemberCategory.INVOKE_DECLARED_CONSTRUCTORS));

        SERIALIZADORES_POR_NOMBRE.forEach(nombre -> pistas.reflection()
                .registerTypeIfPresent(cargador, nombre, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS));

        // El script Lua de los contadores va embebido como cadena en el adaptador, no como
        // recurso, así que no hay nada que incluir. Si algún día se moviera a un fichero .lua,
        // aquí haría falta un hints.resources().registerPattern(...) y sería fácil olvidarlo.
    }
}
