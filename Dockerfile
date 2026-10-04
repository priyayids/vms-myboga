# Stage 1: build the jar with Maven
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app

# Resolve dependencies first so source-only changes reuse the cached layer.
COPY pom.xml .
RUN mvn dependency:go-offline -B || true

COPY src ./src
RUN mvn clean package -B -DskipTests


# Stage 2: minimal JRE runtime
FROM eclipse-temurin:21-jre
WORKDIR /app

# curl is only here for the container HEALTHCHECK below.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# Run as an unprivileged user. The container is reachable from the internet
# through the host nginx, and it shares a host with other production stacks,
# so a container-escape bug should not land straight in root on that host.
# uid/gid 10001 is fixed because the bind-mounted ./logs and ./data/qr-codes
# directories on the host must be chowned to the same id.
RUN groupadd --gid 10001 app \
 && useradd --uid 10001 --gid 10001 --home-dir /app --shell /usr/sbin/nologin app

COPY --from=build /app/target/visitor-middleware-*.jar app.jar

RUN mkdir -p /app/logs /app/data/qr-codes \
 && chown -R app:app /app

USER app:app

ENV PORT=8080 \
    TZ=Asia/Jakarta \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseContainerSupport -Djava.security.egd=file:/dev/./urandom -Dfile.encoding=UTF-8"

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=5 \
  CMD curl -fsS http://127.0.0.1:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
