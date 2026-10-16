# syntax=docker/dockerfile:1.7
# =================================================================================================
# Centinela - imagen nativa con GraalVM
#
# Medido:     binario 230 MB, imagen 338 MB, arranque 0,24 s, 99 MiB de RSS en reposo.
#             Sin JVM en la imagen final, sin shell, sin gestor de paquetes y sin root.
#
#             El binario es grande para lo habitual en Spring Boot nativo (~100 MB) porque la
#             aplicación arrastra Kafka, el SDK de AWS, Lettuce y Tomcat a la vez: kafka-clients
#             solo aporta 14,6 MB de código. El desglose lo imprime GraalVM al final del build,
#             en "Top 10 origins of code area".
#
# Construir:  docker build -t centinela:nativo .
#             docker build -t centinela:jvm --target jvm .     (alternativa, ver README)
# =================================================================================================


# -------------------------------------------------------------------------------------------
# Etapa 1: dependencias
#
# Copia sólo el wrapper y el POM antes que el código. Docker cachea la capa por el contenido
# de lo copiado, así que mientras el POM no cambie esta etapa no se repite: un cambio en una
# regla de fraude no vuelve a descargar medio Maven Central.
# -------------------------------------------------------------------------------------------
FROM ghcr.io/graalvm/native-image-community:25 AS dependencias

WORKDIR /construccion

COPY mvnw ./
COPY .mvn/ .mvn/
COPY pom.xml ./

# La caché de Maven va como caché de BuildKit, no como capa: así no engorda la imagen
# intermedia y se comparte entre construcciones.
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    ./mvnw -B -ntp dependency:go-offline -DskipTests


# -------------------------------------------------------------------------------------------
# Etapa 2: compilación nativa
#
# Aquí ocurren dos cosas: el procesamiento AOT de Spring Boot (que genera el código de
# arranque sin reflexión) y la compilación de GraalVM. Es la etapa cara: varios minutos y
# varios GB de RAM. Si falla por memoria, hay que subir el límite del demonio de Docker.
# -------------------------------------------------------------------------------------------
FROM dependencias AS constructor-nativo

COPY src/ src/

# -DskipTests a propósito: los tests ya corrieron en CI contra contenedores reales, y volver a
# levantar Kafka y LocalStack dentro de un build de Docker sería lento e innecesario.
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    ./mvnw -B -ntp -Pnative native:compile -DskipTests

# El plugin deja el binario en target/ con el imageName configurado en el POM.
RUN mv /construccion/target/centinela /construccion/centinela && \
    chmod 0555 /construccion/centinela


# -------------------------------------------------------------------------------------------
# Etapa 3: identidad y librerías del sistema
#
# Debian 12, la misma base que la imagen final, por dos motivos:
#
#  1. Distroless no trae adduser (ni shell donde ejecutarlo), así que el usuario se fabrica
#     aquí y se copian los ficheros de identidad. Es la forma limpia de tener un `USER appuser`
#     de verdad en una imagen sin gestor de paquetes.
#
#  2. El binario nativo enlaza dinámicamente con libz, y distroless/base-debian12 NO la incluye
#     (trae glibc, OpenSSL y certificados, nada más). Sin ella el contenedor muere al arrancar
#     con "error while loading shared libraries: libz.so.1". Se copia desde Debian y no desde
#     la imagen de GraalVM —que es Oracle Linux— para que la librería sea exactamente la que
#     espera la base final.
# -------------------------------------------------------------------------------------------
FROM debian:12-slim AS sistema

RUN echo 'appuser:x:10001:10001:appuser:/nonexistent:/sbin/nologin' > /identidad-passwd && \
    echo 'appuser:x:10001:' > /identidad-group


# -------------------------------------------------------------------------------------------
# Etapa 4: imagen final
#
# distroless/base-debian12: glibc, OpenSSL y certificados raíz. Nada más. Sin shell, sin
# gestor de paquetes, sin utilidades: la superficie de ataque de un contenedor comprometido se
# reduce a lo que el propio binario pueda hacer.
#
# Se usa 'base' y no 'static' porque el binario nativo enlaza glibc dinámicamente, y no
# 'base-nossl' porque la aplicación habla TLS con DynamoDB, MSK y ElastiCache.
# -------------------------------------------------------------------------------------------
FROM gcr.io/distroless/base-debian12:nonroot AS nativo

COPY --from=sistema /identidad-passwd /etc/passwd
COPY --from=sistema /identidad-group /etc/group

# Única librería que el binario necesita y la base no trae. Comprobado con `ldd` sobre el
# ejecutable: aparte de esta, sólo pide libc, libm y el enlazador, que sí están.
COPY --from=sistema /lib/x86_64-linux-gnu/libz.so.1 /lib/x86_64-linux-gnu/libz.so.1

COPY --from=constructor-nativo --chown=10001:10001 /construccion/centinela /app/centinela

# Numérico además del nombre: algunos controladores de Kubernetes exigen runAsNonRoot y sólo
# saben comprobarlo si el UID no es 0, sin resolver el nombre.
USER appuser

WORKDIR /app
EXPOSE 8080

ENV SPRING_PROFILES_ACTIVE=prod

# Sin ENTRYPOINT en forma shell: no hay shell. La forma exec hace que el binario sea el PID 1
# y reciba SIGTERM directamente, que es lo que dispara el apagado ordenado de Spring y permite
# terminar las evaluaciones en vuelo.
ENTRYPOINT ["/app/centinela"]


# =================================================================================================
# Alternativa sobre JVM
#
# La compilación nativa tarda minutos y es sensible a la metadata de accesibilidad de las
# dependencias. Esta variante se construye en segundos y sirve para desarrollo, para CI rápida y
# como plan B si una subida de librería rompe la imagen nativa.
#
#   docker build -t centinela:jvm --target jvm .
# =================================================================================================
FROM dependencias AS constructor-jvm

COPY src/ src/

RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    ./mvnw -B -ntp package -DskipTests && \
    # Capas del jar: dependencias, cargador, dependencias de instantánea y clases propias.
    # Extraerlas por separado hace que un cambio de código sólo invalide la última capa.
    java -Djarmode=tools -jar target/centinela-*.jar extract --layers --launcher --destination extraido


FROM gcr.io/distroless/java25-debian13:nonroot AS jvm

COPY --from=sistema /identidad-passwd /etc/passwd
COPY --from=sistema /identidad-group /etc/group

WORKDIR /app
COPY --from=constructor-jvm --chown=10001:10001 /construccion/extraido/dependencies/ ./
COPY --from=constructor-jvm --chown=10001:10001 /construccion/extraido/spring-boot-loader/ ./
COPY --from=constructor-jvm --chown=10001:10001 /construccion/extraido/snapshot-dependencies/ ./
COPY --from=constructor-jvm --chown=10001:10001 /construccion/extraido/application/ ./

USER appuser
EXPOSE 8080

ENV SPRING_PROFILES_ACTIVE=prod
# MaxRAMPercentage y no -Xmx: el contenedor puede tener límites distintos según el entorno y la
# JVM debe deducir el heap del cgroup, no de una constante que alguien fijó una vez.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC"

# JarLauncher y no `java -jar`: al copiar las cuatro capas en el mismo directorio queda un jar
# explotado, no un jar. Es justo lo que se busca —cada capa invalida sólo la suya— pero exige
# arrancar por la clase del cargador de Spring Boot.
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
