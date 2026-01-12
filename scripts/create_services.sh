#!/bin/bash

# Base Spring Boot Version
SPRING_BOOT_VERSION="3.2.1"
JAVA_VERSION="17"

# Function to create a project
create_project() {
    SERVICE_NAME=$1
    PACKAGE_NAME="com.decode.$(echo $SERVICE_NAME | tr '-' '_')"
    PACKAGE_DIR="src/main/java/com/decode/$(echo $SERVICE_NAME | tr '-' '/')"
    
    echo "Creating service: $SERVICE_NAME"
    mkdir -p "$SERVICE_NAME/$PACKAGE_DIR"
    mkdir -p "$SERVICE_NAME/src/main/resources"
    mkdir -p "$SERVICE_NAME/src/test/java"

    # Minimal Generic POM
    cat > "$SERVICE_NAME/pom.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>${SPRING_BOOT_VERSION}</version>
        <relativePath/> <!-- lookup parent from repository -->
    </parent>
    <groupId>com.decode</groupId>
    <artifactId>${SERVICE_NAME}</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <name>${SERVICE_NAME}</name>
    <description>Decode service: ${SERVICE_NAME}</description>
    <properties>
        <java.version>${JAVA_VERSION}</java.version>
    </properties>
    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
EOF

    # Main Application Class
    CLASS_NAME="$(echo $SERVICE_NAME | awk -F- '{for(i=1;i<=NF;i++) $i=toupper(substr($i,1,1)) substr($i,2); }1' | tr -d ' ' | tr -d '\n')Application"
    
    cat > "$SERVICE_NAME/$PACKAGE_DIR/${CLASS_NAME}.java" <<EOF
package ${PACKAGE_NAME};

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ${CLASS_NAME} {

    public static void main(String[] args) {
        SpringApplication.run(${CLASS_NAME}.class, args);
    }

}
EOF

    # Dockerfile
    cat > "$SERVICE_NAME/Dockerfile" <<EOF
FROM eclipse-temurin:17-jdk-alpine
VOLUME /tmp
ARG JAR_FILE=target/*.jar
COPY \${JAR_FILE} app.jar
ENTRYPOINT ["java","-jar","/app.jar"]
EOF

}

# Create Services
create_project "api-gateway"
create_project "ingestion-engine"
create_project "code-parser"
create_project "vectorizer-service"
create_project "context-orchestrator"
create_project "documentation-hub"

# Customize API Gateway POM (Add generic Gateway dependency replacement)
sed -i '' 's/spring-boot-starter-web/spring-boot-starter-webflux/g' api-gateway/pom.xml
# Simple insertion for gateway dependency using awk or sed after the webflux dep
sed -i '' '/spring-boot-starter-webflux/a\
        <dependency>\
            <groupId>org.springframework.cloud</groupId>\
            <artifactId>spring-cloud-starter-gateway</artifactId>\
        </dependency>' api-gateway/pom.xml

# Add Spring Cloud Dependency Management to API Gateway
sed -i '' '/<properties>/a\
        <spring-cloud.version>2023.0.0</spring-cloud.version>' api-gateway/pom.xml

sed -i '' '/<\/dependencies>/a\
    <dependencyManagement>\
        <dependencies>\
            <dependency>\
                <groupId>org.springframework.cloud</groupId>\
                <artifactId>spring-cloud-dependencies</artifactId>\
                <version>\${spring-cloud.version}</version>\
                <type>pom</type>\
                <scope>import</scope>\
            </dependency>\
        </dependencies>\
    </dependencyManagement>' api-gateway/pom.xml

echo "Services created."
