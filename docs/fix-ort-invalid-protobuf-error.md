# Fix: ORT_INVALID_PROTOBUF Error

## Error

```
UnsatisfiedDependencyException: Error creating bean with name 'vectorStore'
Caused by: ORT_INVALID_PROTOBUF: Failed to load
in TransformersEmbeddingModelAutoConfiguration.class
```

## Root Cause

The `context-orchestrator` service is trying to load an ONNX model for embeddings, but:
1. The model file doesn't exist at `/models/model.onnx` in the Docker container
2. The model file is corrupted or invalid
3. **Most importantly**: `context-orchestrator` doesn't actually need embeddings!

## Why This Happens

- `context-orchestrator` has `spring-ai-transformers-spring-boot-starter` dependency
- `spring.ai.embedding.transformer.enabled: true` in `application.yaml`
- Spring AI tries to auto-configure `TransformersEmbeddingModel` for embeddings
- The ONNX model file is missing or invalid, causing `ORT_INVALID_PROTOBUF` error

## Solution: Remove Embeddings from context-orchestrator

**`context-orchestrator` only needs `ChatModel` for LLM calls, not embeddings.**

Embeddings are handled by `vectorizer-service`, not `context-orchestrator`.

### 1. Remove Transformer Dependency from `pom.xml`

```xml
<!-- Remove this dependency - context-orchestrator doesn't need embeddings -->
<!-- <dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-transformers-spring-boot-starter</artifactId>
    ...
</dependency> -->
```

### 2. Disable Transformer in `application.yaml`

```yaml
spring:
  ai:
    embedding:
      transformer:
        enabled: false  # Disable - context-orchestrator doesn't need embeddings
```

### 3. Remove ONNX Model Configuration

Remove the ONNX model URI configuration since it's not needed:

```yaml
# Remove these lines:
# onnx:
#   model-uri: file:/models/model.onnx
#   tokenizer-uri: file:/models/tokenizer.json
```

## Why This Works

- **`context-orchestrator`**: Only needs `ChatModel` for LLM chat completions
- **`vectorizer-service`**: Handles all embedding creation and vectorization
- **No ONNX needed**: `context-orchestrator` doesn't create embeddings, so no ONNX model needed

## Architecture Clarification

| Service | Purpose | Needs Embeddings? | Needs ONNX? |
|---------|---------|-------------------|-------------|
| `context-orchestrator` | LLM chat completions, agent orchestration | ❌ No | ❌ No |
| `vectorizer-service` | Create embeddings, store in Qdrant | ✅ Yes | ✅ Yes |

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
- ❌ No `ORT_INVALID_PROTOBUF` error
- ❌ No `TransformersEmbeddingModel` auto-configuration attempts
