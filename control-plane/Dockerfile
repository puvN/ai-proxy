FROM gradle:8.8-jdk21 AS build

WORKDIR /app
COPY . .
RUN gradle :control-plane:bootJar --no-daemon

FROM eclipse-temurin:21-jre

WORKDIR /app

ADD https://repo1.maven.org/maven2/io/opentelemetry/javaagent/opentelemetry-javaagent/2.9.0/opentelemetry-javaagent-2.9.0.jar /app/opentelemetry-javaagent.jar

COPY --from=build /app/control-plane/build/libs/*.jar app.jar

ENV PORT=8081
EXPOSE 8081

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
