# syntax=docker/dockerfile:1
# ---- Build stage: compile + package the Spring Boot jar with Maven ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
# Copy pom first so dependency resolution is cached when only src changes
COPY pom.xml .
RUN mvn -B -q dependency:go-offline || true
COPY src ./src
# Tests run in CI / locally (need Docker); skip here for a fast, deterministic image build
RUN mvn -B -DskipTests package

# ---- Runtime stage: slim JRE only ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
