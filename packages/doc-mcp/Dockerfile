# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jdk-alpine AS builder

WORKDIR /build

COPY pom.xml .
COPY .mvn .mvn
RUN --mount=type=cache,target=/root/.m2 \
    mvn dependency:go-offline -q

COPY src ./src

RUN --mount=type=cache,target=/root/.m2 \
    mvn package -DskipTests -q

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine AS runtime

RUN apk add --no-cache fontconfig ttf-dejavu

WORKDIR /app

RUN addgroup -S docpilot && adduser -S docpilot -G docpilot
USER docpilot

COPY --from=builder /build/target/*.jar app.jar

RUN mkdir -p /app/data/uploads /app/data/registries /app/data/sessions /app/data/revisions /app/data/sqlite /app/data/legacy-archive /app/logs

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --start-period=45s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", \
  "--enable-native-access=ALL-UNNAMED", \
  "--add-opens", "java.base/java.lang=ALL-UNNAMED", \
  "--add-opens", "java.base/java.lang.reflect=ALL-UNNAMED", \
  "--add-opens", "java.base/java.util=ALL-UNNAMED", \
  "--add-opens", "java.base/java.io=ALL-UNNAMED", \
  "--add-opens", "java.base/java.math=ALL-UNNAMED", \
  "--add-opens", "java.xml/com.sun.org.apache.xerces.internal.jaxp=ALL-UNNAMED", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-jar", "/app/app.jar"]
