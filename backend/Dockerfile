FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests package
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S incident && adduser -S incident -G incident
WORKDIR /app
COPY --from=build /build/target/incident-ops-0.1.0.jar app.jar
USER incident
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
