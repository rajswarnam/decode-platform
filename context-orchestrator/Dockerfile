ARG MAVEN_BASE=maven:3.9.6-eclipse-temurin-17
ARG RUNTIME_BASE=artifactory.us.bank-dns.com:5000/usb/eclipse-temurin-jdk17:2026.01.18
FROM ${MAVEN_BASE} AS build
WORKDIR /app
COPY . .
RUN mvn -q -DskipTests clean package

FROM ${RUNTIME_BASE}
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8089
ENTRYPOINT ["java", "-jar", "app.jar"]
