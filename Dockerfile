FROM maven:3.9-eclipse-temurin-21 AS build
ARG SERVICE
WORKDIR /build
COPY ${SERVICE}/pom.xml pom.xml
COPY ${SERVICE}/src src
RUN mvn -B -DskipTests package && cp target/*.jar /app.jar

FROM eclipse-temurin:21-jre
COPY --from=build /app.jar /app.jar
USER 10001
ENTRYPOINT ["java", "-jar", "/app.jar"]
