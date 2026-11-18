FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml pom.xml
COPY src src
RUN mvn -B -DskipTests package && cp target/*.jar /app.jar

FROM eclipse-temurin:21-jre
COPY --from=build /app.jar /app.jar
USER 10001
ENTRYPOINT ["java", "-jar", "/app.jar"]
