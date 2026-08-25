# Centinela

Motor de detección de fraude transaccional en tiempo real. Consume autorizaciones de Kafka,
las evalúa dentro del presupuesto de latencia de la autorización y emite un veredicto con la
evidencia que lo sostiene.

**Estado:** iteración 5 — listo para producción. Imagen nativa con GraalVM sobre distroless,
ecosistema completo en `docker compose`, configuración 12-Factor por perfiles. 163 tests
unitarios (sin contenedores, ~7 s) + 26 de integración contra Kafka, LocalStack y Redis reales.

---

## Stack

| Pieza | Versión | Nota |
|---|---|---|
| Java | **25 LTS** (`java.version` en el POM) | Ver [ADR-0001](#adr-0001) |
| Spring Boot | 4.1.1 | Spring Framework 7.0.9, Jackson 3.1.5 |
| Spring Kafka | 4.1.1 | kafka-clients 4.2.1 |
| AWS SDK for Java | 2.54.2 | `DynamoDbAsyncClient` sobre Netty |
| Testcontainers | 2.0.5 | Kafka + LocalStack + Redis |
| MapStruct | 1.6.3 | GA; 1.7.0 sigue en beta |
| ArchUnit | 1.5.0 | reglas del hexágono como test |
| GraalVM Native Build Tools | 1.1.8 | gestionado por el parent de Spring Boot |
| Imagen de compilación | `ghcr.io/graalvm/native-image-community:25` | no existe variante para JDK 26 |
| Imagen final | `gcr.io/distroless/base-debian12` | glibc + OpenSSL + certificados, sin shell |

Todas las versiones se comprobaron contra Maven Central el 2026-08-24 y son las últimas
**GA**. Se descartaron deliberadamente `spring-boot-starter-parent:4.2.0-M1` y
`mapstruct:1.7.0.Beta2`: un milestone no es "lo último disponible", es código sin soporte.

---

## Arranque rápido

```bash
./mvnw test      # 163 unitarios, sin Docker, ~7 s
./mvnw verify    # + 26 de integración con contenedores reales, ~45 s
```

API REST (además del consumo por Kafka):

```
POST /api/v1/evaluaciones              evalúa una transacción y devuelve el veredicto
GET  /api/v1/evaluaciones/{idTx}       rastro de auditoría de una decisión
GET  /api/v1/perfiles/{idCliente}      perfil de comportamiento (sólo lectura)
```

Los errores salen como Problem Details (RFC 9457) con un `codigo` estable pensado para
programar contra él, en lugar de parsear el texto.

Los tests de integración necesitan un demonio Docker en marcha. Para ejecutar la aplicación y
construir la imagen nativa, ver [Levantar el ecosistema](#levantar-el-ecosistema) e
[Imagen nativa](#imagen-nativa).

> **JDK.** El build exige JDK 25 o superior (regla de `maven-enforcer-plugin`). Si `java -version`
> devuelve otra cosa, apunta `JAVA_HOME` a tu instalación de 25.

### Configuración: 12-Factor por perfiles

| Fichero | Para qué |
|---|---|
| `application.yml` | estructura y valores por defecto aptos para desarrollo; **todo** lo que cambia entre despliegues sale por variable de entorno |
| `application-local.yml` | `docker compose`: LocalStack, broker local, tiempos relajados y trazas al 100 % |
| `application-prod.yml` | **sin valores por defecto para nada crítico**: si falta una variable, la aplicación no arranca |

Que un contenedor no levante es infinitamente preferible a que levante apuntando al broker
equivocado o escribiendo en la tabla de otro entorno.

**Ningún secreto vive en un fichero.** Las contraseñas —Redis, JAAS de MSK— entran por variable
de entorno desde Secrets Manager. Y las credenciales de AWS **no se configuran en producción**:
el SDK usa su cadena por defecto, que en AWS resuelve al rol de la tarea (IRSA en EKS, task role
en ECS) y rota solo. `AWS_ACCESS_KEY_ID` en el entorno de un despliegue real es un hallazgo de
auditoría, no una configuración; en `docker compose` sí aparece, porque LocalStack exige
credenciales y acepta cualquiera.

---

## Estructura

```
com.centinela.fraude
├── dominio                      ← núcleo puro, cero dependencias fuera del JDK
│   ├── modelo                   Transaccion, EvaluacionDeFraude, Decision, NivelDeRiesgo,
│   │                            SenalDeRiesgo, PerfilDeComportamiento
│   ├── modelo.vo                Dinero, Geolocalizacion, Comercio, InstrumentoDePago (sealed),
│   │                            IdTransaccion, IdCliente, IdDispositivo, PuntajeDeRiesgo,
│   │                            Canal, ClaveDeVelocidad, DimensionDeVelocidad
│   ├── regla                    MotorDeReglas, ReglaDeFraude, ContextoDeEvaluacion,
│   │                            ResultadoDeRegla (sealed), PoliticaDeRiesgo
│   ├── regla.catalogo           las 9 reglas de detección
│   ├── puerto.entrada           EvaluarTransaccionUseCase
│   ├── puerto.salida            RepositorioDePerfiles, ContadorDeVelocidad,
│   │                            ActaDeDecisiones, BandejaDeSalida, PublicadorDeDecisiones
│   ├── excepcion                jerarquía sellada de errores de dominio
│   └── compartido               guardas de invariantes
├── aplicacion                   ← Java plano + SLF4J, sin anotaciones de framework
│   └── servicio                 EvaluadorDeFraudeService, RelevoDeBandejaDeSalida
└── infraestructura              ← la única capa que conoce Kafka, AWS y Spring
    ├── adaptador.entrada.contrato  TransaccionEvento y su traductor, compartidos por los dos
    │                               adaptadores de entrada
    ├── adaptador.entrada.kafka     ConsumidorDeTransacciones
    ├── adaptador.entrada.rest      controladores + manejadores de Problem Details
    ├── adaptador.salida.kafka      PublicadorDeDecisionesKafka, DecisionEvento
    ├── adaptador.salida.dynamodb   perfiles, acta de decisiones y bandeja de salida
    ├── adaptador.salida.redis      ContadorDeVelocidadRedis (script Lua atómico)
    └── config                      propiedades tipadas y la raíz de composición
```

La dirección de las dependencias **está verificada como test**
(`ArquitecturaHexagonalTest`): si alguien importa Spring desde el dominio o desde la capa de
aplicación, el build falla.

---

## El motor de reglas

```
caso de uso                    motor                      reglas
───────────                    ─────                      ──────
1. pide contadores  ────►  contadoresNecesarios()  ──►  contadoresQueNecesita()
2. resuelve E/S en paralelo (perfil + contadores) sobre hilos virtuales
3. arma ContextoDeEvaluacion
4. ejecuta          ────►  ejecutar(contexto)      ──►  evaluar(contexto)  ← función pura
                           agrega con switch            ResultadoDeRegla (sellado)
                           exhaustivo
```

### Catálogo

| Código | Aporte base | Qué detecta |
|---|---:|---|
| `VIAJE_IMPOSIBLE` | 400 | Desplazamiento no factible desde la última posición conocida |
| `RAFAGA_POR_DISPOSITIVO` | 260 | Card testing: lote de tarjetas probado desde una máquina |
| `RAFAGA_POR_CLIENTE` | 200 | Cascada de cargos tras una toma de cuenta |
| `DESVIACION_DE_IMPORTE` | 120/220/340 | Importe fuera del gasto habitual, en tres tramos |
| `DISPOSITIVO_DESCONOCIDO` | 180 | Dispositivo nunca visto para este titular |
| `MARCA_TEMPORAL_INCOHERENTE` | 150 | Marca de tiempo futura: intento de esquivar las ventanas |
| `PAIS_INUSUAL` | 120 (+60) | País no frecuentado; extra si además cruza frontera |
| `INSTRUMENTO_DE_RIESGO` | 90/150 | Billetera sin tokenizar o transferencia irreversible |
| `COMERCIO_ALTO_RIESGO` | 90 | MCC de categoría sensible con el que el titular no opera |

Los pesos están repartidos de forma que **ninguna señal aislada llega a la banda de rechazo**:
hacen falta al menos dos indicios fuertes. Es una calibración deliberada contra los falsos
positivos, y se cambia desde `PoliticaDeRiesgo` sin tocar código.

### Los cuatro desenlaces de una regla

`ResultadoDeRegla` es sellado y distingue cuatro cosas que se suelen confundir en un booleano:

- **Disparada** — encontró evidencia.
- **No disparada** — miró y no encontró nada. La transacción pasó ese control.
- **No aplicable** — la regla no era pertinente (huella de dispositivo en un cajero).
- **Degradada** — debía aplicar pero le faltaban datos.

La cuarta es la que justifica el diseño. Si "degradada" se colapsara con "no disparada", una
caída de la caché de contadores haría que todas las reglas de ráfaga devolvieran "nada" y el
motor parecería sano mientras deja pasar el ataque. Al distinguirlas, la degradación viaja hasta
`EvaluacionDeFraude.advertencias()` y una decisión tomada a ciegas es distinguible de una
tomada con cobertura completa.

---

## Flujo integrado

```
fraude.transacciones.v1        POST /api/v1/evaluaciones
        │                              │
        ▼                              ▼
 ConsumidorDeTransacciones ─► Traductor ─► EvaluarTransaccionUseCase
        │ (ack manual)                          │
        │                                       ├─► DynamoDB: perfil (lectura)
        │                                       ├─► Redis: contadores (INCR atómico)
        │                                       ├─► DynamoDB: ACTA (auditoría + outbox,
        │                                       │              una sola transacción)
        ▼ fallo                                 └─► DynamoDB: perfil (aprendizaje, v+1)
 DefaultErrorHandler
   ├── transitorio ──► reintento exponencial con jitter (3 intentos)
   └── no reintentable ──► fraude.transacciones.dlq.v1

 RelevoDeBandejaDeSalida (@Scheduled, cada 500 ms)
   └─► índice disperso de pendientes ──► publica ──► fraude.decisiones.v1
                                          └─ sólo al acusar el broker: REMOVE estado
```

**Kafka ya no está en el camino caliente.** El caso de uso asienta la decisión y termina; la
publicación la hace el relevo aparte. Eso quita la espera del acuse del broker de la ventana de
autorización y, sobre todo, elimina el hueco por el que se perdía una decisión cuando la
publicación fallaba después de haber confirmado el mensaje de entrada.

### Qué se reintenta y qué no

Un fallo transitorio —DynamoDB con un pico, el broker recolocando particiones— merece
reintentos con espera creciente y jitter. Un mensaje malformado, no: reintentarlo mantiene la
partición bloqueada durante todo el backoff y retrasa a las transacciones legítimas que vienen
detrás. `EventoInvalidoException` y `ViolacionDeInvariante` se declaran no reintentables y van
directas a la cola de descartes.

### La cola de descartes conserva la evidencia

Un mensaje llega a la DLQ por dos caminos y el valor no es el mismo:

- **Falló la deserialización** → se republican los **bytes originales** tal cual. Son la única
  evidencia de qué mandó el emisor; volver a serializarlos la destruiría.
- **Falló la validación o la evaluación** → el valor es el DTO ya deserializado y se publica
  como JSON legible.

`SerializadorDeDescartes` distingue los dos casos con un `switch` sobre patrones. Al triar la
DLQ, el tipo real del error está en la cabecera `kafka_dlt-exception-cause-fqcn`; la de primer
nivel es siempre el envoltorio de Spring Kafka.

---

## Levantar el ecosistema

Toda la infraestructura (Kafka en KRaft, LocalStack con las tablas ya creadas, Redis):

```bash
docker compose up -d
```

La aplicación desde el IDE o con Maven contra esa infraestructura:

```bash
./mvnw spring-boot:run
```

Todo junto, con la aplicación compilada nativa dentro de compose:

```bash
docker compose --profile app up -d --build
```

Comprobar que responde y ver el arranque:

```bash
curl -s localhost:8080/actuator/health
```

```bash
docker logs centinela-app | head -20
```

Enviar una transacción por la API:

```bash
curl -s -X POST localhost:8080/api/v1/evaluaciones -H 'Content-Type: application/json' -d '{"idTransaccion":"tx-1","idCliente":"cli-1","importe":120.00,"moneda":"EUR","instrumento":{"tipo":"TARJETA","bin":"454213","ultimosCuatro":"1881","marca":"VISA","presente":false},"comercio":{"id":"com-100","nombre":"Libreria","mcc":"5942","pais":"ES"},"canal":"WEB","origen":{"latitud":40.4168,"longitud":-3.7038,"pais":"ES"},"idDispositivo":"disp-1","ocurridaEn":"2026-08-24T12:00:00Z"}'
```

Y bajarlo todo:

```bash
docker compose --profile app down -v
```

> Las tablas de DynamoDB las crea `infra/localstack/crear-tablas.sh`, que LocalStack ejecuta
> solo al arrancar. Incluye el **índice disperso** del outbox: sin él el relevo no encuentra
> nada pendiente y las decisiones no se publican nunca, sin ningún error visible.

---

## Imagen nativa

```bash
docker build -t centinela:nativo .
```

Medido en esta máquina, no estimado:

| | |
|---|---|
| Compilación de GraalVM | 3 min 11 s |
| Binario | 230 MB |
| Imagen final | 338 MB |
| **Arranque** | **0,24 s** |
| RSS en reposo | 99 MiB |
| `POST /api/v1/evaluaciones` extremo a extremo | 68 ms (incluye el arranque de `curl`) |

El binario es grande para lo habitual en Spring Boot nativo (~100 MB): la aplicación arrastra
Kafka, el SDK de AWS, Lettuce y Tomcat a la vez, y sólo `kafka-clients` aporta 14,6 MB de
código. GraalVM imprime el desglose al terminar, en `Top 10 origins of code area`.

Alternativa sobre JVM, para desarrollo, CI rápida o como plan B si una subida de librería rompe
la compilación nativa:

```bash
docker build -t centinela:jvm --target jvm .
```

Compilar el binario nativo sin Docker (requiere GraalVM 25 en el `PATH`):

```bash
./mvnw -Pnative native:compile -DskipTests
```

### Qué hace falta registrar, y qué no

El analizador de GraalVM recorre el código estáticamente: encuentra solo todo lo que se alcanza
por llamada directa. Sólo hay que registrar lo que se resuelve por nombre en tiempo de ejecución.

| Origen | Cómo se cubre |
|---|---|
| Spring (beans, controladores, `@ConfigurationProperties`) | procesamiento AOT del `spring-boot-maven-plugin` |
| AWS SDK v2, Lettuce | metadata propia en `META-INF/native-image` |
| `kafka-clients` | **no trae metadata**; la aporta el repositorio de accesibilidad de GraalVM, habilitado en el perfil `native` del POM |
| DTO de cable (`TransaccionEvento`, `DecisionEvento`, respuestas REST) | [`PistasDeImagenNativa`](src/main/java/com/centinela/fraude/infraestructura/config/PistasDeImagenNativa.java) |
| Records **anidados** de `@ConfigurationProperties` | idem — ver la nota de abajo |
| **El motor de reglas** | **nada** — ver abajo |

> **Los records anidados de `@ConfigurationProperties` sí hacen falta.** Spring AOT registra el
> record externo para el enlace, pero Bean Validation lee los campos por reflexión al evaluar
> `@NotBlank`, y los anidados se quedan fuera. El síntoma es un
> `MissingReflectionRegistrationError` sobre un campo concreto —`PropiedadesDeAws$DynamoDb.tablaBandeja`
> fue el primero— **en el arranque del binario, no en la compilación**. El build pasa limpio y
> el contenedor muere al levantar: es el modo de fallo característico de la imagen nativa, y la
> razón por la que no basta con que compile.

**El motor de fraude no necesita una sola pista.** Las nueve reglas se instancian con `new` en
`CatalogoDeReglas.estandar()`, el motor las recibe por constructor y el caso de uso se cablea con
`@Bean` explícitos: no hay escaneo de componentes en el núcleo, ni `ServiceLoader`, ni proxies.
Es la factura a cobrar de haber mantenido el dominio y la aplicación libres de anotaciones
([ADR-0008](#adr-0008--la-capa-de-aplicación-tampoco-lleva-anotaciones)). Un diseño con
`@Component` por regla habría necesitado registrar cada una, y cada regla nueva sería una pista
que alguien acabaría olvidando.

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

### ADR-0002 — Cliente AWS asíncrono, mapeo manual a `AttributeValue`

*Revisa la decisión de la iteración 1, que había optado por el cliente síncrono.*

Se usa `DynamoDbAsyncClient` sobre `netty-nio-client`. El puerto sigue siendo síncrono y el
adaptador hace `join()`, lo cual con hilos virtuales cuesta aparcar un hilo. Lo que aporta el
cliente asíncrono aquí es **control fino de tiempos**: `apiCallTimeout` acota la llamada
completa con sus reintentos y `apiCallAttemptTimeout` cada intento, ambos por debajo del
presupuesto de latencia del motor, sin necesidad de un pool propio que los vigile.

No se usa `dynamodb-enhanced`. Su mapeo por bean necesita reflexión —mala compañera de la
imagen nativa— y aquí todo el modelo son records con value objects (`Dinero`, `Optional`,
conjuntos) que exigirían un conversor por componente: más código que el mapeo manual, y menos
explícito. La forma de cada tabla vive en un único fichero por agregado.

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

### ADR-0007 — Las reglas no hacen E/S: protocolo en dos fases

Cada regla declara qué contadores necesita (`contadoresQueNecesita`) y solo después se la
evalúa contra un contexto ya materializado. Es la decisión central del motor.

Si cada regla consultara su propio dato: la latencia sería la suma de todas las consultas en vez
del máximo, dos reglas que miran el mismo contador harían dos viajes, el presupuesto de latencia
sería imposible de acotar porque dependería de cuántas reglas se dispararan, y probar una regla
exigiría simular su fuente de datos.

Con este diseño cada regla es una **función pura**: los 131 tests corren en 6 segundos sin un
solo contenedor, y el catálogo entero se puede reejecutar sobre datos históricos para recalibrar
sin tocar infraestructura.

### ADR-0008 — La capa de aplicación tampoco lleva anotaciones

Ni `@Service` ni inyección por Spring. El cableado será un `@Bean` explícito en
`infraestructura.config`. No es purismo: la lógica de degradación —qué hacer cuando el perfil no
llega o los contadores fallan— es la parte más difícil de probar y la que más veces evita una
caída. Sin anotaciones se prueba entera con JUnit y Mockito en milisegundos.

Rectifica lo que decía este documento en la iteración 1.

### ADR-0009 — La tarjeta no presente no genera señal

Contraintuitivo: es el segmento con más fraude. Pero también es **el caso base del comercio
electrónico**, y emitir una señal por ella suma el mismo aporte a casi todas las transacciones.
Eso no discrimina entre buenas y malas, solo desplaza la distribución entera y obliga a subir
todos los umbrales para compensar. Una regla que dispara siempre es una constante, no una señal.

El riesgo de la tarjeta no presente se captura donde sí discrimina: dispositivo desconocido,
país inusual, ráfaga y desviación de importe.

### ADR-0010 — Los tiempos de producción no se relajan en `application.yml`

Los tests de integración sobrescriben `centinela.aws.cliente.*` y
`centinela.motor.presupuesto-latencia` con valores holgados. Los del YAML —120 ms de llamada,
150 ms de presupuesto— están calculados para DynamoDB en la misma zona de disponibilidad;
LocalStack a través de la red de Docker es uno o dos órdenes de magnitud más lento y con ellos
toda evaluación saldría degradada por timeout.

Lo que **no** se hace es subir el valor en `application.yml` para que los tests pasen. El
presupuesto de latencia es un requisito del producto, y aflojarlo por conveniencia de la suite
convierte una garantía en un número decorativo. Los tests de integración verifican corrección;
la latencia se mide en un entorno con la topología real.

### ADR-0011 — La atomicidad gana a la segregación de interfaces

`RegistroDeAuditoria` y `PublicadorDeDecisiones` eran dos puertos separados invocando ISP:
auditar y publicar tienen garantías y motivos de cambio distintos. El razonamiento era correcto
y el resultado tenía un agujero — entre "he auditado" y "he publicado" cabe una caída, y la
decisión se perdía sin que nadie se enterase.

El outbox exige que el hecho y la promesa de contarlo caigan juntos o no caiga ninguno, y eso
obliga a que sea **una** escritura. Ahora es un solo puerto, `ActaDeDecisiones`, implementado
con `TransactWriteItems` de DynamoDB sobre las dos tablas. Una interfaz algo más ancha es un
problema de diseño; una decisión de fraude perdida es un problema de negocio.

`PublicadorDeDecisiones` sigue existiendo, pero ya no lo usa el caso de uso: lo usa el relevo.

### ADR-0012 — Bloqueo optimista por contador, no por marca temporal

La primera versión condicionaba la escritura del perfil a `actualizadoEn < :nuevo`. Falla en el
caso que importa: dos evaluaciones concurrentes del mismo cliente caben de sobra en el mismo
milisegundo, y ahí una comparación por fecha no distingue nada.

Ahora el perfil lleva `version` y la escritura condiciona a `version = :anterior`. En conflicto,
el repositorio lanza `ConflictoDeVersion` y el caso de uso relee y reintenta **una** vez; si
vuelve a chocar, abandona. Perder una actualización de perfil es aceptable —la siguiente
transacción la reincorpora—; sobrescribir el aprendizaje de otra evaluación, no.

### ADR-0013 — El perfil sólo aprende de lo que se aprobó sin fricción

`PerfilDeComportamiento.aprendiendoDe` devuelve vacío si la decisión fue rechazar, desafiar o
revisar. Sin esa guarda, un motor que aprende solo es entrenable por el atacante: bastaría con
colar una compra desde Tokio para que Japón pasara a ser país habitual y la siguiente ya no
disparara.

Dos guardas más, en el caso de uso:

- **Sólo si la lectura del perfil fue concluyente.** Escribir a partir del perfil provisional que
  se usa al degradar sustituiría el historial real de un cliente de años por uno en blanco. Un
  pico de latencia de DynamoDB borraría comportamiento aprendido.
- **Nunca propaga.** Un fallo al realimentar no puede tumbar una autorización.

Limitación conocida: un `DESAFIAR` que el titular supera legítimamente tampoco enseña nada,
porque el resultado de la autenticación no vuelve a este motor.

### ADR-0014 — Índice disperso para la bandeja de salida

El relevo pregunta "¿qué queda por publicar?" muchas veces por segundo. Se resuelve con un GSI
cuya clave de partición es `estado`: mientras la entrada está pendiente el atributo existe;
al publicarla se **elimina** y la entrada desaparece del índice sola.

Con un booleano `publicado` el índice contendría el histórico completo de decisiones y el coste
del relevo crecería para siempre. Con el atributo ausente, el coste es proporcional a lo que
queda por hacer.

### ADR-0015 — La imagen nativa se compila sobre Java 25, no 26

No es una preferencia: **no existe imagen de GraalVM community para JDK 26**. Los tags
publicados de `ghcr.io/graalvm/native-image-community` llegan al 25, que además es la versión
sobre la que Spring Boot 4.1 certifica la compilación nativa.

Coincide con [ADR-0001](#adr-0001--java-25-lts-como-línea-base-no-java-26) por un motivo
distinto y comprobable. Cuando salga la imagen para 26, migrar es cambiar el tag del Dockerfile
y la propiedad `java.version` del POM; el código del dominio compila igual en ambas.

### ADR-0016 — `gzip` en el productor de Kafka, no `zstd`

zstd, lz4 y snappy son bibliotecas JNI: cargan un `.so` desde dentro del propio jar en tiempo de
ejecución. En una imagen nativa ese recurso no se incluye salvo que se declare a mano, y
`kafka-clients` **no trae metadata de native-image** que lo haga. El síntoma sería un
`UnsatisfiedLinkError` en la primera publicación, ya en producción.

`gzip` usa `java.util.zip`, que es del JDK y funciona igual compilado. El coste es un ratio de
compresión peor sobre mensajes que son JSON pequeño — aceptable. Sobre la JVM, la variable
`KAFKA_COMPRESSION=zstd` lo cambia sin tocar código, y el perfil `local` ya lo hace.

### ADR-0017 — `-march=compatibility` en el binario nativo

Por defecto GraalVM compila para el micro donde corre el build (`x86-64-v3` o superior). Una
imagen se publica en un registro y se ejecuta donde toque; un binario optimizado para el
portátil de quien compiló revienta con `SIGILL` en una instancia más antigua, y el fallo no se
parece en nada a su causa.

Para una imagen distribuible, la portabilidad vale más que el último margen de rendimiento.

### ADR-0018 — Distroless con un `appuser` fabricado, no el `nonroot` de la imagen

`gcr.io/distroless/base-debian12` trae glibc, OpenSSL y certificados raíz, y nada más: sin
shell, sin gestor de paquetes, sin utilidades. Si alguien logra ejecución dentro del contenedor
no encuentra con qué moverse.

No se usa `base-nossl` porque la aplicación habla TLS con DynamoDB, MSK y ElastiCache; ni
`static`, porque el binario enlaza glibc dinámicamente.

El usuario se fabrica en una etapa intermedia y se copian `/etc/passwd` y `/etc/group`:
distroless no trae `adduser` ni shell donde ejecutarlo. El UID es 10001 y no 0, que es lo que
comprueba `runAsNonRoot` en Kubernetes sin resolver nombres.

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

## Pendiente

1. **Autenticación y autorización de la API REST.** Los endpoints de consulta exponen datos de
   fraude y perfiles de comportamiento, y hoy están abiertos. Es lo primero antes de exponerla.
2. Ventana deslizante en los contadores de velocidad. La actual es fija: un atacante que conozca
   el umbral puede repartir la ráfaga a caballo de dos ventanas y colar hasta el doble.
3. Arrendamiento por entrada en la bandeja de salida. Hoy dos relevos concurrentes pueden
   publicar la misma decisión; se acepta porque la entrega ya es al-menos-una-vez y los
   consumidores deduplican por identificador de transacción.
4. Umbrales de `NivelDeRiesgo` movidos a `PoliticaDeRiesgo` (los pesos de las reglas ya están en
   configuración; las bandas siguen siendo constantes del enum).
5. Métricas de negocio en Micrometer: tasa por decisión, reglas disparadas, evaluaciones
   degradadas, profundidad de la bandeja de salida, latencia por percentil.
6. Realimentar el desenlace de un `DESAFIAR`, que hoy no vuelve al motor (ver ADR-0013).
7. Terraform con la infraestructura real. `infra/localstack/crear-tablas.sh` define las tablas y
   el índice; hay que llevarlo a IaC para que los dos no se desvíen.

---

## Notas de build conocidas

- El aviso `The following options were not recognized by any processor:
  '[mapstruct.unmappedTargetPolicy, mapstruct.defaultComponentModel]'` es esperado mientras no
  exista ningún `@Mapper`; desaparece con el primer mapper.
- Los tests de integración requieren Docker. Sin demonio, `./mvnw verify` falla al arrancar los
  contenedores; `./mvnw test` sigue funcionando.
- Los contenedores se declaran como `@Bean` en `ContenedoresDeIntegracion` y todas las clases de
  integración comparten la misma configuración de contexto a propósito: así el caché de
  contextos de Spring Test los reutiliza y arrancan una sola vez para toda la suite.
- **La compilación nativa tarda minutos y pide varios GB de RAM.** Si falla con un error de
  memoria, hay que subir el límite del demonio de Docker. Los tests no se ejecutan dentro del
  build de la imagen (`-DskipTests`): ya corrieron en CI contra contenedores reales, y levantar
  Kafka y LocalStack dentro de un `docker build` sería lento e innecesario.
- Los avisos `sun.misc.Unsafe::objectFieldOffset will be removed` y
  `RecomputeFieldValue.ArrayIndexScale ... failed` durante la compilación nativa vienen de Netty
  y del SDK de AWS. Son conocidos y no impiden que el binario funcione.
- La imagen nativa **no tiene shell**, así que `docker exec ... sh` no funciona y el
  `healthcheck` de compose está desactivado a propósito. Para sondearla se llama a
  `/actuator/health` desde fuera. Meter `curl` en la imagen para tener un healthcheck sería
  cambiar seguridad por comodidad.
- **`distroless/base-debian12` no incluye `libz`**, y el binario nativo la enlaza dinámicamente.
  Sin copiarla, el contenedor muere al arrancar con `error while loading shared libraries:
  libz.so.1` y ningún log de la aplicación, porque nunca llega a ejecutarse. Se copia desde una
  etapa `debian:12-slim` —la misma distro que la base final— y no desde la imagen de GraalVM,
  que es Oracle Linux. Para comprobar si hace falta alguna más: `ldd` sobre el ejecutable.
- `apache/kafka-native` **no trae los scripts de `/opt/kafka/bin`**. La sonda habitual con
  `kafka-topics.sh` falla con `not found` y deja el servicio en `unhealthy` indefinidamente con
  el broker funcionando detrás; el `healthcheck` de compose usa `nc -z` por eso.
