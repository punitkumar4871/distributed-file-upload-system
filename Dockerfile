# ─────────────────────────────────────────────────────────────────────────────
# Stage 1: Build the JAR with Maven
# ─────────────────────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /app

# Copy pom.xml first — Docker caches this layer so dependencies
# aren't re-downloaded on every build if pom.xml didn't change
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source and build
COPY src ./src
RUN mvn clean package -DskipTests -B

# ─────────────────────────────────────────────────────────────────────────────
# Stage 2: Slim runtime image
# ─────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Copy only the built JAR from stage 1
COPY --from=builder /app/target/upload-system.jar app.jar

# Create upload directories inside the container
RUN mkdir -p uploads/temp uploads/final

# Expose the port Spring Boot listens on
EXPOSE 8080

# Health check for Docker / Kubernetes
HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=3 \
  CMD wget -q --spider http://localhost:8080/api/health || exit 1

# Run the application
ENTRYPOINT ["java", "-jar", "/app/app.jar"]