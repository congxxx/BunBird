FROM maven:3.9.16-eclipse-temurin-21 AS build
WORKDIR /build
COPY backend/pom.xml ./pom.xml
COPY backend/src ./src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/bunbird-control-0.1.0.jar /app/control.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/control.jar"]
