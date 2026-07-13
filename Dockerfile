# syntax=docker/dockerfile:1
FROM eclipse-temurin:26-jdk AS build
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B dependency:go-offline
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -DskipTests package

FROM eclipse-temurin:26-jre
WORKDIR /app
COPY --from=build /app/target/aletheia-*.jar app.jar
EXPOSE 8431
ENTRYPOINT ["java", "-jar", "app.jar"]
