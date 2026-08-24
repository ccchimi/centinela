# Centinela

Motor de detección de fraude transaccional en tiempo real. Consume autorizaciones de Kafka,
las evalúa dentro del presupuesto de latencia de la autorización y emite un veredicto con la
evidencia que lo sostiene.

**Estado:** iteración 1 — esqueleto del proyecto y dominio. Sin adaptadores todavía.

---

## Stack

| Pieza | Versión | Nota |
|---|---|---|
| Java | **25 LTS** (`java.version` en el POM) | Ver [ADR-0001](#adr-0001) |
| Spring Boot | 4.1.1 | Spring Framework 7.0.9, Jackson 3.1.5 |
| Spring Kafka | 4.1.1 | kafka-clients 4.2.1 |
| AWS SDK for Java | 2.54.2 | DynamoDB Enhanced Client |
| Testcontainers | 2.0.5 | Kafka + LocalStack |
| MapStruct | 1.6.3 | GA; 1.7.0 sigue en beta |
| ArchUnit | 1.5.0 | reglas del hexágono como test |
| GraalVM Native Build Tools | 1.1.8 | gestionado por el parent de Spring Boot |

Todas las versiones se comprobaron contra Maven Central el 2026-08-24 y son las últimas
**GA**. Se descartaron deliberadamente `spring-boot-starter-parent:4.2.0-M1` y
`mapstruct:1.7.0.Beta2`: un milestone no es "lo último disponible", es código sin soporte.

---

## Arranque rápido

```bash
./mvnw test
```

Compilación nativa (requiere GraalVM):

```bash
./mvnw -Pnative native:compile
```

Ejecución con LocalStack y un broker local:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

> **JDK.** El build exige JDK 25 o superior (regla de `maven-enforcer-plugin`). Si `java -version`
> devuelve otra cosa, apunta `JAVA_HOME` a tu instalación de 25.

---

## Estructura

```
com.centinela.fraude
├── dominio                      ← núcleo puro, cero dependencias fuera del JDK
│   ├── modelo                   Transaccion, EvaluacionDeFraude, Decision, NivelDeRiesgo,
│   │                            SenalDeRiesgo, PerfilDeComportamiento
│   ├── modelo.vo                Dinero, Geolocalizacion, Comercio, InstrumentoDePago (sealed),
│   │                            IdTransaccion, IdCliente, IdDispositivo, PuntajeDeRiesgo, Canal
│   ├── puerto.entrada           EvaluarTransaccionUseCase
│   ├── puerto.salida            RepositorioDePerfiles, ContadorDeVelocidad,
│   │                            PublicadorDeDecisiones, RegistroDeAuditoria
│   ├── excepcion                jerarquía sellada de errores de dominio
│   └── compartido               guardas de invariantes
├── aplicacion                   ← casos de uso (pendiente)
└── infraestructura              ← adaptadores Kafka / DynamoDB / ElastiCache / REST (pendiente)
```

La dirección de las dependencias **está verificada como test**
(`ArquitecturaHexagonalTest`): si alguien importa Spring desde el dominio, el build falla.

---

## Decisiones de arquitectura

### ADR-0001 — Java 25 LTS como línea base, no Java 26

Todo lo que este proyecto necesita del lenguaje —virtual threads, records, sealed types,
pattern matching con deconstrucción de registros, patrones sin nombre (`_`)— es **final desde
Java 21/22**. Java 26 aporta HTTP/3 en el cliente HTTP, mejoras de rendimiento en G1 y la
retirada de la Applet API: nada que este motor use.

Frente a eso, Java 25 es LTS con soporte a largo plazo, y **Spring Boot 4.1 certifica la
compilación nativa con GraalVM sobre Java 25**, no sobre 26. Para un sistema que decide
autorizaciones de pago, esa diferencia pesa más que el número de versión.

Cambiar de opinión cuesta una línea: `<java.version>26</java.version>` en el POM. El código del
dominio compila igual en ambas.

> **String Templates no existen.** JEP 430/459/465 estuvo en preview en Java 21 y 22 y fue
> **retirado en Java 23**; no está en 25 ni en 26 y no hay sucesor. El equivalente idiomático
> es `String::formatted`, que es lo que usa todo el dominio.

> **Structured Concurrency sigue en preview** (sexta iteración en Java 26). No se activa
> `--enable-preview`: una API en preview puede cambiar entre releases y compilar un binario
> nativo de producción contra ella es deuda garantizada. La ejecución concurrente de reglas se
> hará con virtual threads y `ExecutorService`, que es API final.

### ADR-0002 — Cliente AWS síncrono sobre virtual threads, no cliente asíncrono

Se excluye `netty-nio-client` y se usa `apache-client` con `DynamoDbClient` síncrono. Con
`spring.threads.virtual.enabled: true`, bloquear en una llamada a DynamoDB cuesta lo que cuesta
aparcar un hilo virtual, y a cambio el código de las reglas se lee de arriba abajo en vez de
encadenar `CompletableFuture`. Desde JEP 491 (Java 24) ni siquiera `synchronized` fija el hilo
portador, que era la última objeción real a este enfoque.

Beneficio secundario: menos dependencias en la imagen nativa.

### ADR-0003 — Particionado de Kafka por cliente

`Transaccion.claveDeParticion()` devuelve el identificador del titular. Eso garantiza orden por
cliente y un único consumidor por cliente, que es lo que hace correctos los contadores de
velocidad y la regla de viaje imposible. El coste conocido es la partición caliente de un
cliente muy activo; se acepta.

### ADR-0004 — Sin Lombok

Los records cubren lo que Lombok aportaba (`@Value`, `@Getter`, `@EqualsAndHashCode`,
constructores) con soporte del lenguaje en vez de manipulación del AST, y el dominio es
inmutable de punta a punta, así que no hay setters que generar. Quitarlo elimina además el
riesgo recurrente de que un processor que toca internals del compilador bloquee una subida de
JDK.

Si el equipo lo prefiere para `@Slf4j` en los adaptadores, se añade así:

```xml
<dependency>
  <groupId>org.projectlombok</groupId>
  <artifactId>lombok</artifactId>
  <version>1.18.46</version>
  <scope>provided</scope>
</dependency>
```

...y **hay que añadirlo también a `annotationProcessorPaths`** en `maven-compiler-plugin`, o el
processor no se ejecutará: al declarar rutas explícitas se desactiva el descubrimiento por
classpath.

### ADR-0005 — ElastiCache y DynamoDB, cada uno para lo suyo

No es "o DynamoDB o ElastiCache", son dos accesos con perfiles distintos:

- **ElastiCache (Valkey/Redis)** — contadores de velocidad. Incremento atómico con expiración,
  latencia de un dígito de milisegundo, dato desechable. Timeout de 80 ms: si no responde, el
  motor degrada esa señal y sigue.
- **DynamoDB** — perfil de comportamiento y rastro de auditoría. Lectura por clave y escritura
  duradera con retención regulatoria.

### ADR-0006 — Validación en dos anillos

`jakarta.validation` vive en los DTO de los adaptadores de entrada, donde protege la frontera y
produce un 400 legible. Las invariantes del modelo viven en los constructores compactos de los
records, sin anotaciones, y hacen que un objeto de dominio inválido sea **inconstruible**. Son
dos capas con propósitos distintos, no una duplicada.

---

## Detalles del dominio que conviene conocer

- **`Dinero` normaliza la escala en el constructor.** El `equals` de un record delega en
  `BigDecimal.equals`, que es sensible a la escala: sin normalizar, `10.0` y `10.00` serían
  claves distintas de un mapa. Redondeo `HALF_EVEN` para no sesgar los agregados.
- **`IdCliente.toString()` devuelve el valor enmascarado.** Los objetos de dominio acaban en
  logs y en mensajes de excepción; el `toString` por defecto de un record volcaría el
  identificador en claro. Para el valor real hay que pedir `valor()` explícitamente.
- **`InstrumentoDePago` es una jerarquía sellada.** Añadir un instrumento nuevo rompe la
  compilación en cada `switch` que deba decidir sobre él, en vez de caer en un `default` que lo
  trataría como riesgo bajo por accidente.
- **`Transaccion` no valida que su fecha no sea futura.** Hacerlo exigiría leer el reloj del
  sistema dentro del núcleo y volvería no deterministas los tests. La tolerancia de desfase es
  una decisión del caso de uso, que recibe un `Clock` inyectado.
- **`EvaluacionDeFraude` comprueba su propia coherencia**: rechaza construirse si el nivel no
  corresponde al puntaje. Un mapeo mal escrito en un adaptador no puede persistir una decisión
  que se contradice a sí misma.
- **Un cliente sin perfil no es un error.** `PerfilDeComportamiento.nuevoPara(...)` devuelve un
  perfil vacío y `esFiable()` avisa de que las señales de desviación aún no significan nada.
  Tratarlo como excepción penalizaría a todo cliente nuevo.

---

## Pendiente (iteración 2)

1. `EvaluadorDeFraudeService` implementando el puerto de entrada, con presupuesto de latencia y
   ejecución concurrente de reglas independientes sobre virtual threads.
2. Motor de reglas: `ReglaDeFraude` como interfaz de estrategia y el catálogo inicial (viaje
   imposible, desviación de importe, ráfaga por dispositivo, MCC sensible, dispositivo nuevo).
3. Adaptador de entrada Kafka con `DefaultErrorHandler`, reintentos con backoff y topic de
   descartes.
4. Adaptadores de salida: DynamoDB Enhanced (perfiles, auditoría) y ElastiCache (velocidad).
5. `@RestControllerAdvice` con `ProblemDetail`, mapeando la jerarquía sellada de errores con un
   `switch` exhaustivo.
6. Pruebas de integración con Testcontainers: Kafka y LocalStack (DynamoDB) mediante
   `@ServiceConnection`.
7. Umbrales de `NivelDeRiesgo` movidos a configuración versionada.
8. Metadatos de reflexión para GraalVM y verificación del binario nativo.

---

## Notas de build conocidas

- El aviso `The following options were not recognized by any processor:
  '[mapstruct.unmappedTargetPolicy, mapstruct.defaultComponentModel]'` es esperado mientras no
  exista ningún `@Mapper`; desaparece con el primer mapper.
- `spring.json.value.default.type` en `application.yml` apunta a
  `...adaptador.entrada.kafka.TransaccionEvento`, que llega en la iteración 2. La propiedad es
  inerte hasta que exista un listener.
