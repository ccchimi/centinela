# Inicialización

Cómo levantar Centinela entero en tu máquina, desde un clon recién hecho, y qué mirar una vez
está en marcha.

El entorno local no es una maqueta: es el mismo binario nativo que iría a producción, contra
Kafka, DynamoDB y Redis reales (simulados por LocalStack), con trazas y métricas. Coste cero.

- [Requisitos](#requisitos)
- [1. Clonar](#1-clonar)
- [2. Levantar todo](#2-levantar-todo)
- [3. Comprobar que está arriba](#3-comprobar-que-está-arriba)
- [4. Primera petición](#4-primera-petición)
- [5. Hacer que el motor decida](#5-hacer-que-el-motor-decida)
- [6. La otra puerta: Kafka](#6-la-otra-puerta-kafka)
- [7. Grafana](#7-grafana)
- [8. Jaeger](#8-jaeger)
- [9. Apagar](#9-apagar)
- [Otras formas de trabajar](#otras-formas-de-trabajar)
- [Problemas frecuentes](#problemas-frecuentes)

---

## Requisitos

Sólo **Docker Desktop**. Nada más: ni Java ni Maven, porque la compilación ocurre dentro del
contenedor.

> **Dale al demonio de Docker al menos 8 GB de RAM.** La compilación nativa con GraalVM pide
> varios GB y, si se queda corta, falla con un error de memoria que no dice claramente que el
> problema es el límite de Docker.

Los comandos usan `curl.exe` en lugar de `curl` porque así funcionan igual en PowerShell y en
Git Bash. En Linux y macOS, `curl` a secas.

---

## 1. Clonar

```bash
git clone https://github.com/ccchimi/centinela.git && cd centinela
```

## 2. Levantar todo

Un solo comando levanta el entorno simulado completo: infraestructura, aplicación y
observabilidad.

```bash
docker compose --profile completo up -d --build
```

**La primera vez tarda entre 5 y 10 minutos**, porque compila el binario nativo. Las siguientes
son cuestión de segundos: la caché de capas de Docker conserva las dependencias.

Durante la compilación verás avisos de `sun.misc.Unsafe::objectFieldOffset will be removed` y
`RecomputeFieldValue.ArrayIndexScale ... failed`. Vienen de Netty y del SDK de AWS, son
conocidos y no impiden que el binario funcione.

## 3. Comprobar que está arriba

```bash
docker compose --profile completo ps
```

Los siete servicios deben aparecer `Up`. Cinco de ellos añaden `(healthy)`.

`centinela` no lleva healthcheck **a propósito**: la imagen distroless no tiene shell ni `curl`,
y meter utilidades dentro sólo para sondearla sería cambiar seguridad por comodidad. Se sondea
desde fuera:

```bash
curl.exe -s http://localhost:8080/actuator/health
```

| Servicio | Puerto | Para qué |
|---|---|---|
| `centinela` | 8080 | La API |
| `grafana` | 3000 | Métricas |
| `jaeger` | 16686 | Trazas |
| `prometheus` | 9090 | Series temporales |
| `kafka` | 9092 | Broker (KRaft, sin ZooKeeper) |
| `localstack` | 4566 | DynamoDB simulado |
| `redis` | 6379 | Contadores de velocidad |

## 4. Primera petición

La API exige la cabecera `X-API-KEY`. En local la clave por defecto es
`clave-de-desarrollo-no-usar-en-produccion`, y el arranque avisa a gritos de que se está usando.

```bash
curl.exe -s -X POST http://localhost:8080/api/v1/evaluaciones -H "Content-Type: application/json" -H "X-API-KEY: clave-de-desarrollo-no-usar-en-produccion" -d '{"idTransaccion":"tx-1","idCliente":"cli-demo","importe":120.00,"moneda":"EUR","instrumento":{"tipo":"TARJETA","bin":"454213","ultimosCuatro":"1881","marca":"VISA","presente":false},"comercio":{"id":"com-100","nombre":"Libreria","mcc":"5942","pais":"ES"},"canal":"WEB","origen":{"latitud":40.4168,"longitud":-3.7038,"pais":"ES"},"idDispositivo":"disp-1","ocurridaEn":"2026-08-24T12:00:00Z"}'
```

```json
{
  "idTransaccion": "tx-1",
  "puntaje": 0,
  "nivel": "BAJO",
  "decision": "APROBAR",
  "bloqueada": false,
  "degradada": true,
  "senales": [],
  "advertencias": [
    "VIAJE_IMPOSIBLE: sin datos suficientes (el perfil no tiene posición ni momento anterior con los que comparar)",
    "DESVIACION_DE_IMPORTE: sin datos suficientes (el perfil solo acumula 0 operaciones, insuficientes para una media significativa)",
    "DISPOSITIVO_DESCONOCIDO: sin datos suficientes (el perfil no tiene dispositivos conocidos con los que comparar)",
    "PAIS_INUSUAL: sin datos suficientes (el perfil no tiene países habituales con los que comparar)"
  ],
  "evaluadaEn": "2026-08-26T00:29:24Z",
  "latenciaMicros": 5460
}
```

`degradada: true` con cuatro advertencias **es la respuesta correcta en la primera petición**: el
cliente no tiene historial y las reglas no tienen con qué comparar. El motor no inventa un
veredicto con datos que no tiene, dice exactamente qué no pudo evaluar y por qué.

## 5. Hacer que el motor decida

Para ver el motor trabajando de verdad hace falta que el perfil del cliente tenga historial.
Seis operaciones normales:

```bash
for i in 0 1 2 3 4 5; do curl.exe -s -o /dev/null -X POST http://localhost:8080/api/v1/evaluaciones -H "Content-Type: application/json" -H "X-API-KEY: clave-de-desarrollo-no-usar-en-produccion" -d "{\"idTransaccion\":\"n-$i\",\"idCliente\":\"cli-demo\",\"importe\":$((95+i*7)).00,\"moneda\":\"EUR\",\"instrumento\":{\"tipo\":\"TARJETA\",\"bin\":\"454213\",\"ultimosCuatro\":\"1881\",\"marca\":\"VISA\",\"presente\":false},\"comercio\":{\"id\":\"com-100\",\"nombre\":\"Libreria\",\"mcc\":\"5942\",\"pais\":\"ES\"},\"canal\":\"WEB\",\"origen\":{\"latitud\":40.4168,\"longitud\":-3.7038,\"pais\":\"ES\"},\"idDispositivo\":\"disp-1\",\"ocurridaEn\":\"2026-08-24T1$i:00:00Z\"}"; done
```

El perfil que ha aprendido:

```bash
curl.exe -s http://localhost:8080/api/v1/perfiles/cli-demo -H "X-API-KEY: clave-de-desarrollo-no-usar-en-produccion"
```

```json
{
  "cliente": "****demo",
  "importeMedio": "109.00 EUR",
  "importeMaximoHistorico": "123.00 EUR",
  "paisesHabituales": ["ES"],
  "dispositivosConocidos": ["disp-1"],
  "transaccionesObservadas": 5,
  "fiable": false
}
```

El identificador del cliente sale enmascarado (`****demo`). Es dato personal y no se devuelve en
claro ni siquiera por la API interna.

**Y ahora una transacción anómala**: 5.000 EUR, desde Moscú, con un dispositivo desconocido, a
las pocas horas de haber operado en Madrid.

```bash
curl.exe -s -X POST http://localhost:8080/api/v1/evaluaciones -H "Content-Type: application/json" -H "X-API-KEY: clave-de-desarrollo-no-usar-en-produccion" -d '{"idTransaccion":"anomala","idCliente":"cli-demo","importe":5000.00,"moneda":"EUR","instrumento":{"tipo":"TARJETA","bin":"454213","ultimosCuatro":"1881","marca":"VISA","presente":false},"comercio":{"id":"com-999","nombre":"Joyeria","mcc":"5944","pais":"RU"},"canal":"WEB","origen":{"latitud":55.7558,"longitud":37.6173,"pais":"RU"},"idDispositivo":"disp-desconocido","ocurridaEn":"2026-08-24T16:00:00Z"}'
```

```json
{
  "idTransaccion": "anomala",
  "puntaje": 400,
  "nivel": "MEDIO",
  "decision": "DESAFIAR",
  "senales": [
    {
      "codigo": "VIAJE_IMPOSIBLE",
      "descripcion": "Desplazamiento de 3440 km desde la última operación, no factible en el tiempo transcurrido",
      "aporte": 400
    }
  ]
}
```

Ahí está el motor haciendo su trabajo: detecta el viaje imposible, lo cuantifica en 400 puntos y
decide `DESAFIAR`. La señal viene con la evidencia que la sostiene, no sólo con el veredicto.

Y para recuperar una decisión ya emitida:

```bash
curl.exe -s http://localhost:8080/api/v1/evaluaciones/anomala -H "X-API-KEY: clave-de-desarrollo-no-usar-en-produccion"
```

## 6. La otra puerta: Kafka

La API REST no es la única entrada. El motor también consume del topic
`fraude.transacciones.v1`, que es como llegarían las autorizaciones en producción.

```bash
docker run --rm -i --network centinela_default apache/kafka:3.9.1 bash -c 'echo "{\"idTransaccion\":\"tx-kafka-1\",\"idCliente\":\"cli-9\",\"importe\":890.00,\"moneda\":\"EUR\",\"instrumento\":{\"tipo\":\"TARJETA\",\"bin\":\"454213\",\"ultimosCuatro\":\"1881\",\"marca\":\"VISA\",\"presente\":false},\"comercio\":{\"id\":\"com-200\",\"nombre\":\"Electronica\",\"mcc\":\"5732\",\"pais\":\"ES\"},\"canal\":\"WEB\",\"origen\":{\"latitud\":40.4168,\"longitud\":-3.7038,\"pais\":\"ES\"},\"idDispositivo\":\"disp-9\",\"ocurridaEn\":\"2026-08-24T12:00:00Z\"}" | /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka:19092 --topic fraude.transacciones.v1'
```

Compruébalo en el log:

```bash
docker logs centinela-app 2>&1 | grep tx-kafka-1
```

Y las decisiones que el motor publica, del lado de salida:

```bash
docker run --rm --network centinela_default apache/kafka:3.9.1 /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:19092 --topic fraude.decisiones.v1 --from-beginning --max-messages 5
```

> Estos dos comandos usan `apache/kafka:3.9.1` y **no** el contenedor que ya está corriendo. El
> de compose usa `apache/kafka-native`, que es una imagen nativa: sólo lleva el broker y **no
> trae los scripts de `/opt/kafka/bin`**. Intentar `docker exec centinela-kafka
> kafka-console-producer.sh` falla con "not found".

| Topic | Contenido |
|---|---|
| `fraude.transacciones.v1` | Entrada: autorizaciones a evaluar |
| `fraude.decisiones.v1` | Salida: veredictos con su evidencia |
| `fraude.transacciones.dlq.v1` | Descartes: lo que no se pudo deserializar ni evaluar |

## 7. Grafana

**<http://localhost:3000>** — entra directo, sin login, y abre el panel de Centinela.

La fuente de datos y los 14 paneles se aprovisionan al arrancar desde `infra/grafana/`, que vive
en el repositorio y no dentro de un volumen de Docker.

| Bloque | Paneles |
|---|---|
| **Estado** | Arriba, tiempo en marcha, memoria en uso, peticiones/s, errores 5xx/s |
| **API** | Throughput por endpoint, latencia p50/p95/p99, respuestas por código, peticiones en vuelo |
| **Kafka** | Consumo y producción, retraso del consumidor (lag) |
| **Proceso** | Memoria, CPU, latencia de comandos de Redis |

Prometheus hace *scraping* de `/actuator/prometheus` cada 5 segundos. No hay *push*: es el mismo
modelo que se usaría en producción, así que lo que se ve aquí es representativo.

Para que los gráficos tengan forma, genera algo de carga:

```bash
for i in $(seq 1 60); do curl.exe -s -o /dev/null -X POST http://localhost:8080/api/v1/evaluaciones -H "Content-Type: application/json" -H "X-API-KEY: clave-de-desarrollo-no-usar-en-produccion" -d "{\"idTransaccion\":\"carga-$i\",\"idCliente\":\"cli-$((i%7))\",\"importe\":$((30+i*13)).00,\"moneda\":\"EUR\",\"instrumento\":{\"tipo\":\"TARJETA\",\"bin\":\"454213\",\"ultimosCuatro\":\"1881\",\"marca\":\"VISA\",\"presente\":false},\"comercio\":{\"id\":\"com-100\",\"nombre\":\"Libreria\",\"mcc\":\"5942\",\"pais\":\"ES\"},\"canal\":\"WEB\",\"origen\":{\"latitud\":40.4168,\"longitud\":-3.7038,\"pais\":\"ES\"},\"idDispositivo\":\"disp-1\",\"ocurridaEn\":\"2026-08-24T12:00:00Z\"}"; done
```

> El panel de percentiles necesita los *buckets* del histograma, que sólo se publican en el
> perfil `local` (`management.metrics.distribution.percentiles-histogram`). En producción cada
> bucket es una serie más por combinación de etiquetas, y eso multiplica la cardinalidad y el
> coste de almacenamiento.

## 8. Jaeger

**<http://localhost:16686>** — elige el servicio **`centinela`** y pulsa *Find Traces*.

En local el muestreo es al 100 %, así que aparecen todas las peticiones.

Lo que merece la pena abrir:

| Traza | Qué muestra |
|---|---|
| `http post /api/v1/evaluaciones` | Una evaluación completa de principio a fin |
| `task planificadorDelRelevo.publicarPendientes` | **El patrón outbox hecho visible**: la tarea programada con los spans `fraude.decisiones.v1 send` colgando de ella, uno por decisión publicada |
| `evalsha` | El script Lua de los contadores de velocidad en Redis, en torno a 0,2 ms |

Esa segunda traza es la interesante. La decisión se guardó en DynamoDB dentro de la misma
transacción que la auditoría, y el relevo la publica a Kafka después: por eso el `send` no
cuelga de la petición HTTP, sino de la tarea de relevo.

### Lo que no verás, y no es un fallo

- **Las llamadas a DynamoDB.** El AWS SDK v2 no está instrumentado por Micrometer; haría falta
  la instrumentación OpenTelemetry del SDK.
- **El motor de reglas.** No lleva anotaciones a propósito, para mantener el dominio limpio
  (ver ADR-0008 en el [README](README.md)). El motor se observa por su efecto: la latencia del
  span HTTP que lo envuelve y la decisión publicada en Kafka.

Ambas cosas están en el punto 6 de *Pendiente*, junto con las métricas de negocio.

## 9. Apagar

```bash
docker compose --profile completo down -v
```

El `-v` borra los volúmenes. Los tres servicios de observabilidad son efímeros por diseño
—almacenamiento en memoria y sin volúmenes persistentes—, así que la máquina queda como estaba.
Los paneles no se pierden: viven en `infra/grafana/`, versionados.

---

## Otras formas de trabajar

Los perfiles de compose se solapan, de modo que `completo` contiene a `app` y a `obs`:

| Comando | Levanta |
|---|---|
| `docker compose up -d` | Kafka, LocalStack, Redis |
| `docker compose --profile app up -d` | ...más la aplicación nativa |
| `docker compose --profile obs up -d` | ...más Jaeger, Prometheus y Grafana |
| `docker compose --profile completo up -d` | Todo |

Lo habitual mientras se desarrolla es levantar la infraestructura y la observabilidad en compose
y ejecutar la aplicación desde el IDE. Para eso sí hace falta **JDK 25**:

```bash
docker compose --profile obs up -d
```

```bash
./mvnw spring-boot:run
```

El perfil `local` es el activo por defecto y ya apunta a `localhost:4318`, así que las trazas
siguen llegando a Jaeger igual que si la aplicación estuviera en un contenedor.

### Tests

> **Hace falta JDK 25.** Con una versión anterior, el `maven-enforcer-plugin` corta el build
> antes de compilar nada:
>
> ```
> Rule 0: RequireJavaVersion failed with message:
> Se requiere JDK 25 o superior para compilar Centinela.
> ```
>
> Es la regla haciendo su trabajo, no un fallo del proyecto. Instalarlo en Windows:
> `winget install --id EclipseAdoptium.Temurin.25.JDK`

```bash
./mvnw test
```

Unitarios (176), sin Docker, en torno a 2 minutos. Para la suite completa, incluyendo los de
integración con Testcontainers:

```bash
./mvnw verify
```

#### Sin instalar JDK 25

Si no querés tocar el JDK de tu máquina, los tests corren dentro de la misma imagen de GraalVM
que usa el `Dockerfile`, que ya lleva JDK 25. Se monta el proyecto y el repositorio local de
Maven para reutilizar las dependencias ya descargadas:

```bash
docker run --rm -v "${PWD}:/proyecto" -v "${HOME}/.m2:/root/.m2" -w /proyecto --entrypoint /bin/bash ghcr.io/graalvm/native-image-community:25 -c "./mvnw -B -ntp test"
```

En Git Bash hay que anteponer `MSYS_NO_PATHCONV=1` para que no reescriba las rutas del montaje.

---

## Problemas frecuentes

| Síntoma | Causa y solución |
|---|---|
| La compilación nativa falla con un error de memoria | Sube el límite de RAM del demonio de Docker a 8 GB o más |
| `docker exec centinela-app sh` da "not found" | La imagen distroless no tiene shell. Es intencionado. Usa `docker logs centinela-app` |
| El target `centinela` sale caído en Prometheus | La aplicación todavía está arrancando. Espera unos segundos tras `up -d` |
| Editas un panel de Grafana y no se refleja | La recarga en caliente no llega a través del bind mount en Docker Desktop: Grafana vigila la fecha de modificación y el montaje no la propaga. `docker compose --profile obs restart grafana` |
| `401 No autorizado` en la API | Falta la cabecera `X-API-KEY` o su valor no es el correcto |
| `docker exec centinela-kafka kafka-topics.sh` da "not found" | `apache/kafka-native` no trae los scripts. Usa un contenedor `apache/kafka:3.9.1` como en el [paso 6](#6-la-otra-puerta-kafka) |
| Las evaluaciones salen siempre `degradada: true` | El perfil del cliente aún no tiene historial. Es correcto: ver el [paso 5](#5-hacer-que-el-motor-decida) |
| `./mvnw test` falla con *"Se requiere JDK 25 o superior"* | Tenés un JDK anterior. Es el `maven-enforcer-plugin` cortando antes de compilar. Instalá el 25 o usa el contenedor: ver [Tests](#tests) |
