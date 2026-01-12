FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /runner
COPY --from=build /app/target/*.jar app.jar
ENV USE_STDIO=false
ENTRYPOINT ["java","-jar","/runner/app.jar"]
