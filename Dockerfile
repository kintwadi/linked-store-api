# syntax=docker/dockerfile:1.6

# ============================================================
#  BACKEND (Spring Boot 3 / Java 17) — Multi-Stage Dockerfile
#  Host: Render (Web Service, private registry)
# ============================================================

# ---------- Stage 1: Maven Build ----------
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

# --- dependency layer caching ---
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -e org.apache.maven.plugins:maven-dependency-plugin:3.6.1:go-offline \
        -DexcludeGroupIds=org.projectlombok

# --- source + compile (skip tests — Render env has no DB at build time) ---
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -e -q -DskipTests -Dmaven.test.skip=true clean package

# --- extract jar layers for better image layer reuse (Spring Boot layered jar) ---
RUN java -Djarmode=layertools -jar target/*.jar extract --destination target/extracted

# ---------- Stage 2: Runtime (Eclipse Temurin JRE 17, slim) ----------
FROM eclipse-temurin:17-jre-jammy AS runtime

ENV LANG=C.UTF-8 \
    TZ=UTC \
    JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=40.0 -XshowSettings:vm -Djava.security.egd=file:/dev/./urandom -Dfile.encoding=UTF-8" \
    SPRING_OUTPUT_ANSI_ENABLED=ALWAYS \
    SERVER_PORT=8080 \
    SERVER_ADDRESS=0.0.0.0

WORKDIR /app

# --- Render convention: create non-root user ---
RUN groupadd --system --gid 1001 app \
 && useradd  --system --uid 1001 --gid app --home /app app \
 && chown -R app:app /app

USER app

# --- copy layer tool output in dependency order for optimal layer caching ---
COPY --from=builder /build/target/extracted/dependencies/   ./
COPY --from=builder /build/target/extracted/spring-boot-loader/ ./
COPY --from=builder /build/target/extracted/snapshot-dependencies/ ./
COPY --from=builder /build/target/extracted/application/    ./

EXPOSE 8080

# --- Health check against Spring MVC /actuator/health or index endpoint ---
#     (NOTE: backend disables actuators by default, so we ping / via HTTP)
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=4 \
    CMD curl -fsS --max-time 3 http://127.0.0.1:8080/actuator/health || \
        curl -fsS --max-time 3 http://127.0.0.1:8080/ || exit 1

ENTRYPOINT [ "sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher" ]
