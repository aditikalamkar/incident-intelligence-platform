# Build stage: compiles with the Maven wrapper. Tests run in CI, not here (they need Docker themselves).
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q package -DskipTests

# Runtime stage: JRE only, non-root user.
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 opspilot
WORKDIR /app
COPY --from=build /src/target/opspilot-*.jar app.jar
USER opspilot
# 8080: API. 8081: actuator (health, Prometheus metrics); keep it internal.
EXPOSE 8080 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
