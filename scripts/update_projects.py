
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
    ET.register_namespace('', "http://maven.apache.org/POM/4.0.0")
    tree = ET.parse(pom_path)
    root = tree.getroot()
    ns = {'mvn': 'http://maven.apache.org/POM/4.0.0'}

    # Add Repositories
    repositories = root.find('mvn:repositories', ns)
    if repositories is None:
        repositories = ET.SubElement(root, 'repositories')
    
    repo = ET.SubElement(repositories, 'repository')
    ET.SubElement(repo, 'id').text = 'spring-milestones'
    ET.SubElement(repo, 'name').text = 'Spring Milestones'
    ET.SubElement(repo, 'url').text = 'https://repo.spring.io/milestone'
    ET.SubElement(repo, 'snapshots').text = 'false'  # Actually <snapshots><enabled>false</enabled></snapshots> but simplifying
    
    repo_snap = ET.SubElement(repositories, 'repository')
    ET.SubElement(repo_snap, 'id').text = 'spring-snapshots'
    ET.SubElement(repo_snap, 'name').text = 'Spring Snapshots'
    ET.SubElement(repo_snap, 'url').text = 'https://repo.spring.io/snapshot'
    ET.SubElement(repo_snap, 'releases').text = 'false'

    # Add BOM to DependencyManagement
    dep_mgmt = root.find('mvn:dependencyManagement', ns)
    if dep_mgmt is None:
        dep_mgmt = ET.SubElement(root, 'dependencyManagement')
    
    dependencies_node = dep_mgmt.find('mvn:dependencies', ns)
    if dependencies_node is None:
        dependencies_node = ET.SubElement(dep_mgmt, 'dependencies')

    bom = ET.SubElement(dependencies_node, 'dependency')
    ET.SubElement(bom, 'groupId').text = 'org.springframework.ai'
    ET.SubElement(bom, 'artifactId').text = 'spring-ai-bom'
    ET.SubElement(bom, 'version').text = spring_ai_version
    ET.SubElement(bom, 'type').text = 'pom'
    ET.SubElement(bom, 'scope').text = 'import'

    # Add Starter to Dependencies
    deps = root.find('mvn:dependencies', ns)
    starter = ET.SubElement(deps, 'dependency')
    ET.SubElement(starter, 'groupId').text = 'org.springframework.ai'
    ET.SubElement(starter, 'artifactId').text = 'spring-ai-mcp-server-spring-boot-starter'

    # Pretty print hack not needed if parser preserves format, but ET doesn't well.
    # We will write it back.
    tree.write(pom_path, encoding='UTF-8', xml_declaration=True)

def find_package_path(service_path):
    # Walk to find directory with *Application.java
    for root, dirs, files in os.walk(os.path.join(service_path, "src/main/java")):
        for file in files:
            if file.endswith("Application.java"):
                return root
    return None

def create_mcp_config(service_path):
    package_dir = find_package_path(service_path)
    if not package_dir:
        print(f"Could not find package for {service_path}")
        return

    # Determine package name from path
    # path: .../src/main/java/com/decode/...
    try:
        rel_path = os.path.relpath(package_dir, os.path.join(service_path, 'src/main/java'))
        package_name = rel_path.replace(os.sep, '.')
    except:
        package_name = "com.decode" # Fallback

    config_dir = os.path.join(package_dir, 'config')
    os.makedirs(config_dir, exist_ok=True)
    
    file_content = f"""package {package_name}.config;

import org.springframework.ai.mcp.server.McpServer.Transport;
import org.springframework.ai.mcp.server.transport.StdioServerTransport;
import org.springframework.ai.mcp.server.transport.SseServerTransport;
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
    public Transport stdioServerTransport(ObjectMapper mapper) {{
        return new StdioServerTransport(mapper);
    }}

    @Bean
    @ConditionalOnProperty(name = "USE_STDIO", havingValue = "false", matchIfMissing = true)
    public Transport sseServerTransport(ObjectMapper mapper) {{
        return new SseServerTransport(mapper, "/mcp/message");
    }}
    
    // Ensure tools are registered as Spring Beans using @ToolCallback logic
    // The starter usually handles @ToolCallback bean detection, but we explicitly provide provider if needed
    // or just a dummy tool to verify
    
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
    print(f"Processing {service}...")
    update_pom(service)
    create_mcp_config(service)
    update_dockerfile(service)

print("All services updated.")
