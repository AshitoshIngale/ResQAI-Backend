# ---- Stage 1: build the JAR from source ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Cache dependencies first
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

# Copy source and build (tests are run by CI, not during image build)
COPY src ./src
RUN mvn -B -q -DskipTests package

# ---- Stage 2: minimal runtime ----
FROM eclipse-temurin:21-jre
WORKDIR /app

# Run as a non-root user
RUN groupadd -r resqai && useradd -r -g resqai resqai
USER resqai

# Render injects PORT; Spring reads it via server.port=${PORT:8080}
ENV PORT=8080
EXPOSE 8080

COPY --from=build /app/target/resqai-backend-*.jar /app/resqai-backend.jar

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD wget -qO- http://127.0.0.1:${PORT}/actuator/health >/dev/null 2>&1 || exit 1

ENTRYPOINT ["sh", "-c", "java -jar /app/resqai-backend.jar --server.port=$PORT"]
