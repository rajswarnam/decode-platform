# Fix: Qdrant Bean Definition Conflict

## Error

```
BeanDefinitionOverrideException: Invalid bean definition with name 'qdrantConnectionDetails' 
defined in class path resource [org/springframework/ai/vectorstore/qdrant/autoconfigure/QdrantVectorStoreAutoConfiguration.class]: 
Cannot register bean definition [...] for bean 'qdrantConnectionDetails' since there is already 
[Root bean: ...] defined in class path resource [org/springframework/ai/autoconfigure/vectorstore/qdrant/QdrantVectorStoreAutoConfiguration.class]
```

## Root Cause

**Version Mismatch**: Mixing Spring AI M6 and 1.0.0 versions causes two different auto-configuration classes to be loaded:

1. **M6 version**: `org.springframework.ai.autoconfigure.vectorstore.qdrant.QdrantVectorStoreAutoConfiguration`
2. **1.0.0 version**: `org.springframework.ai.vectorstore.qdrant.autoconfigure.QdrantVectorStoreAutoConfiguration`

Both try to create a bean named `qdrantConnectionDetails`, causing the conflict.

## Why This Happens

- `spring-ai-starter-vector-store-qdrant`: `1.0.0` (only version available)
- Other Spring AI dependencies: `1.0.0-M6` (milestone versions)
- The M6 BOM or other M6 dependencies bring in the M6 auto-configuration
- The 1.0.0 qdrant starter brings in the 1.0.0 auto-configuration
- Both get loaded, causing the conflict

## Solution

Exclude the **M6 version's auto-configuration** since we're using the 1.0.0 qdrant starter:

### 1. Exclude in `application.yaml`

```yaml
spring:
  autoconfigure:
    exclude:
      - org.springframework.ai.autoconfigure.chat.client.observation.ChatClientObservationAutoConfiguration
      - org.springframework.ai.autoconfigure.vectorstore.qdrant.QdrantVectorStoreAutoConfiguration  # M6 version
```

### 2. Exclude in `ContextOrchestratorApplication.java`

```java
@SpringBootApplication(excludeName = {
    "org.springframework.ai.autoconfigure.chat.client.observation.ChatClientObservationAutoConfiguration",
    "org.springframework.ai.autoconfigure.vectorstore.qdrant.QdrantVectorStoreAutoConfiguration"  // M6 version
})
```

## Why This Works

- **1.0.0 qdrant starter** will use its own auto-configuration: `org.springframework.ai.vectorstore.qdrant.autoconfigure.QdrantVectorStoreAutoConfiguration`
- **M6 auto-configuration** is excluded, preventing the conflict
- Only one bean definition exists, so no conflict

## Alternative: Enable Bean Overriding (Not Recommended)

If you can't exclude, you can enable bean overriding:

```yaml
spring:
  main:
    allow-bean-definition-overriding: true
```

**⚠️ Warning**: This can hide version conflicts and cause unexpected behavior. Prefer exclusion.

## Verify

After applying, rebuild and restart:

```bash
cd context-orchestrator
mvn clean package
docker-compose up -d context-orchestrator
docker logs -f decode-platform-context-orchestrator-1
```

You should see:
- ✅ `Started ContextOrchestratorApplication`
- ❌ No `BeanDefinitionOverrideException`
