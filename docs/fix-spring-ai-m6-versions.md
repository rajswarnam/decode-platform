# Fix: Spring AI M6 Version Configuration

## Issue

You only have access to Spring AI `1.0.0-M6` (milestone version), not `1.0.0` (GA). The `pom.xml` was configured for GA version.

## Solution

Update all Spring AI dependencies to use `1.0.0-M6` version.

## Changes Made

### 1. Updated `pom.xml` Properties

**Before**:
```xml
<properties>
    <spring-ai.version>1.0.0</spring-ai.version>
</properties>
```

**After**:
```xml
<properties>
    <spring-ai.version>1.0.0-M6</spring-ai.version>
</properties>
```

### 2. Updated All Spring AI Dependencies

All Spring AI dependencies now explicitly use `1.0.0-M6`:

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-core</artifactId>
    <version>1.0.0-M6</version>
</dependency>

<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-openai</artifactId>
    <version>1.0.0-M6</version>
</dependency>

<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-vector-store-qdrant</artifactId>
    <version>1.0.0-M6</version>
</dependency>
```

### 3. Updated Spring AI BOM

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-bom</artifactId>
            <version>1.0.0-M6</version>  <!-- Changed from 1.0.0 to M6 -->
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

## Spring AI M6 API for OpenAiChatModel

The `OpenAiChatModelConfig` uses the M6 API:

```java
@Bean
public ChatModel chatModel() {
    OpenAiApi openAiApi = new OpenAiApi(baseUrl, apiKey);
    OpenAiChatOptions options = OpenAiChatOptions.builder()
            .withModel(model)
            .build();
    return new OpenAiChatModel(openAiApi, options);
}
```

## Verify

After updating versions, rebuild:

```bash
cd context-orchestrator
mvn clean package
```

The compilation should now work with M6 dependencies.

## Version Consistency

All Spring AI dependencies are now at `1.0.0-M6`:
- ✅ `spring-ai-core`: `1.0.0-M6`
- ✅ `spring-ai-openai`: `1.0.0-M6`
- ✅ `spring-ai-starter-vector-store-qdrant`: `1.0.0-M6`
- ✅ `spring-ai-transformers-spring-boot-starter`: `1.0.0-M6` (already was)
- ✅ `spring-ai-bom`: `1.0.0-M6`

This ensures all Spring AI dependencies are compatible with each other.
