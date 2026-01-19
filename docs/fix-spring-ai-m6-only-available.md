# Fix: Spring AI 1.0.0 GA Doesn't Exist - Use M6

## The Problem

Maven Central only has milestone versions (M5, M6) for Spring AI. There is **no 1.0.0 GA version** available.

## Solution

Use `1.0.0-M6` for all Spring AI dependencies and exclude the broken observation auto-configuration.

## Changes Made

### 1. Updated All Versions to M6

```xml
<properties>
    <spring-ai.version>1.0.0-M6</spring-ai.version>
</properties>
```

All Spring AI dependencies now use M6:
- `spring-ai-core`: `1.0.0-M6`
- `spring-ai-openai-spring-boot-starter`: `1.0.0-M6`
- `spring-ai-starter-vector-store-qdrant`: `1.0.0-M6`
- `spring-ai-transformers-spring-boot-starter`: `1.0.0-M6`
- `spring-ai-bom`: `1.0.0-M6`

### 2. Exclude Observation Auto-Configuration

Since M6 has the observation issue, exclude it at multiple levels:

**In `pom.xml`** (dependency exclusion):
```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-openai-spring-boot-starter</artifactId>
    <version>${spring-ai.version}</version>
    <exclusions>
        <exclusion>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-autoconfigure-chat-client</artifactId>
        </exclusion>
    </exclusions>
</dependency>
```

**In `ContextOrchestratorApplication.java`**:
```java
@SpringBootApplication(excludeName = {
    "org.springframework.ai.autoconfigure.chat.client.observation.ChatClientObservationAutoConfiguration"
})
```

**In `application.yaml`**:
```yaml
spring:
  autoconfigure:
    exclude:
      - org.springframework.ai.autoconfigure.chat.client.observation.ChatClientObservationAutoConfiguration
```

## Why This Works

1. **All versions match**: Everything is at M6, so no API incompatibilities
2. **Starter handles complexity**: The starter knows the correct M6 API
3. **Observation excluded**: The broken observation classes are excluded at multiple levels
4. **ChatClient still works**: `ChatClientAutoConfiguration` is still active, just without observation

## Verify

After applying, rebuild:

```bash
cd context-orchestrator
mvn clean package
docker-compose up -d context-orchestrator
```

The application should start successfully.
