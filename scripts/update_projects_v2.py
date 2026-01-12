
import os
import xml.etree.ElementTree as ET

services = [
    'api-gateway',
    'ingestion-engine',
    'code-parser',
    'vectorizer-service',
    'context-orchestrator',
    'documentation-hub'
]

spring_ai_version = "1.0.0-M1"

def update_pom(service_path):
    pom_path = os.path.join(service_path, 'pom.xml')
    # Use simple string replacement/insertion to avoid namespace headaches with ElementTree
    with open(pom_path, 'r') as f:
        content = f.read()

    # Add Repositories if not present
    if 'spring-milestones' not in content:
        repo_xml = """<repositories>
        <repository>
            <id>spring-milestones</id>
            <name>Spring Milestones</name>
            <url>https://repo.spring.io/milestone</url>
            <snapshots>
                <enabled>false</enabled>
            </snapshots>
        </repository>
        <repository>
            <id>spring-snapshots</id>
            <name>Spring Snapshots</name>
            <url>https://repo.spring.io/snapshot</url>
            <releases>
                <enabled>false</enabled>
            </releases>
        </repository>
    </repositories>"""
        if '<repositories>' in content:
            # Append to existing
            pass 
        else:
            content = content.replace('</project>', f'{repo_xml}\n</project>')

    # Add Dependency Management
    if 'spring-ai-bom' not in content:
        bom_xml = f"""
            <dependency>
                <groupId>org.springframework.ai</groupId>
                <artifactId>spring-ai-bom</artifactId>
                <version>{spring_ai_version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>"""
        if '<dependencyManagement>' in content:
             content = content.replace('<dependencyManagement>', f'<dependencyManagement>\n    <dependencies>{bom_xml}', 1)
             content = content.replace('<dependencyManagement>\n    <dependencies>', '<dependencyManagement>\n        <dependencies>', 1) # simple fix
             # This is risky string replacement. Safe way: find specific insertion point.
             # Regexp replacement is better.
        else:
            # DependencyManagement usually goes after dependencies
            content = content.replace('</dependencies>', f'</dependencies>\n    <dependencyManagement>\n        <dependencies>{bom_xml}\n        </dependencies>\n    </dependencyManagement>')

    # Add Starter
    if 'spring-ai-mcp-server-spring-boot-starter' not in content:
        starter_xml = """
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-mcp-server-spring-boot-starter</artifactId>
        </dependency>"""
        content = content.replace('</dependencies>', f'    {starter_xml}\n    </dependencies>')

    with open(pom_path, 'w') as f:
        f.write(content)

def is_webflux(service_path):
    with open(os.path.join(service_path, 'pom.xml'), 'r') as f:
        return 'spring-boot-starter-webflux' in f.read()

def find_package_path(service_path):
    for root, dirs, files in os.walk(os.path.join(service_path, "src/main/java")):
        for file in files:
            if file.endswith("Application.java"):
                return root
    return None

def create_mcp_config(service_path):
    package_dir = find_package_path(service_path)
    if not package_dir:
        return
    
    try:
        rel_path = os.path.relpath(package_dir, os.path.join(service_path, 'src/main/java'))
        package_name = rel_path.replace(os.sep, '.')
    except:
        package_name = "com.decode"

    config_dir = os.path.join(package_dir, 'config')
    os.makedirs(config_dir, exist_ok=True)

    webflux = is_webflux(service_path)
    
    sse_import = "org.springframework.ai.mcp.server.transport.WebFluxSseServerTransport" if webflux else "org.springframework.ai.mcp.server.transport.HttpServletSseServerTransport"
    sse_class = "WebFluxSseServerTransport" if webflux else "HttpServletSseServerTransport"
    
    file_content = f"""package {package_name}.config;

import org.springframework.ai.mcp.server.transport.ServerMcpTransport;
import org.springframework.ai.mcp.server.transport.StdioServerTransport;
import {sse_import};
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

@Configuration
public class McpConfig {{

    @Bean
    @ConditionalOnProperty(name = "USE_STDIO", havingValue = "true")
    public ServerMcpTransport stdioServerTransport(ObjectMapper mapper) {{
        return new StdioServerTransport(mapper);
    }}

    @Bean
    @ConditionalOnProperty(name = "USE_STDIO", havingValue = "false", matchIfMissing = true)
    public ServerMcpTransport sseServerTransport(ObjectMapper mapper) {{
        return new {sse_class}(mapper, "/mcp/message");
    }}

    @Bean
    public ToolCallbackProvider toolCallbackProvider() {{
        return new MethodToolCallbackProvider();
    }}
}}
"""
    with open(os.path.join(config_dir, 'McpConfig.java'), 'w') as f:
        f.write(file_content)

def update_dockerfile(service_path):
    content = f"""FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
ENV USE_STDIO=false
ENTRYPOINT ["java","-jar","/app/app.jar"]
"""
    with open(os.path.join(service_path, 'Dockerfile'), 'w') as f:
        f.write(content)

for service in services:
    update_pom(service)
    create_mcp_config(service)
    update_dockerfile(service)

print("All services updated.")
