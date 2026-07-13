# syntax=docker/dockerfile:1
FROM eclipse-temurin:26-jre
WORKDIR /app
COPY target/aletheia-*.jar app.jar
EXPOSE 8431
ENTRYPOINT ["java", "-jar", "app.jar"]
