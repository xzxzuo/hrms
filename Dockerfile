FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -DskipTests dependency:go-offline
COPY src src
RUN mvn -B -DskipTests package
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S hrms && adduser -S hrms -G hrms && apk add --no-cache curl
WORKDIR /app
COPY --from=build /app/target/hrms-1.0.0-SNAPSHOT.jar app.jar
USER hrms
EXPOSE 8080 8081
ENTRYPOINT ["java","-XX:MaxRAMPercentage=70","-jar","app.jar"]
