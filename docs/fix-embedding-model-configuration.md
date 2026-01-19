# Fix: Embedding Model Configuration for Internal Gateway

## Error

```
400 Bad Request: "model not supported for appID=..., model=text-embedding-ada-002"
```

## Root Cause

Spring AI is using the default embedding model `text-embedding-ada-002`, but your internal gateway only supports `gpt-40`.

## What is INTERNAL_EMBEDDING_BASE_URL Used For?

`INTERNAL_EMBEDDING_BASE_URL` is used by Spring AI to make embedding API calls. When `vectorStore.similaritySearch()` is called:

1. **Query text is embedded** using the embedding API
2. **Embedded query is searched** against stored embeddings in Qdrant
3. **Similar documents are returned**

The embedding API endpoint is configured via `spring.ai.openai.embedding.base-url` (or `INTERNAL_EMBEDDING_BASE_URL` environment variable).

## Solution: Configure Embedding Model

Update `application.yaml` to specify the embedding model:

```yaml
spring:
  ai:
    openai:
      embedding:
        enabled: true
        options:
          model: gpt-40  # Use gpt-40 (only model available in your gateway)
```

## Complete Configuration

```yaml
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      base-url: ${INTERNAL_LLM_GATEWAY_BASE_URL:http://llm-gateway-service:8081}
      chat:
        enabled: true
        options:
          model: gpt-40
      embedding:
        enabled: true
        options:
          model: gpt-40  # Configure embedding model
        base-url: ${INTERNAL_EMBEDDING_BASE_URL}  # Optional: separate URL for embeddings
```

## Note About gpt-40

**Important**: `gpt-40` is typically a **chat completion model**, not an embedding model. However, if your internal gateway supports it for embeddings, this configuration will work.

If your gateway doesn't support `gpt-40` for embeddings, you have two options:

### Option 1: Use a Different Embedding Model

If your gateway supports other embedding models (like `text-embedding-3-small`), use that:

```yaml
spring:
  ai:
    openai:
      embedding:
        options:
          model: text-embedding-3-small  # If available in your gateway
```

### Option 2: Disable Embeddings and Use Alternative

If no embedding models are available, you might need to:
- Use a different vector search approach
- Or configure a separate embedding service

## Verify

After applying, rebuild and restart:

```bash
cd context-orchestrator
mvn clean package
docker-compose up -d context-orchestrator
docker logs -f decode-platform-context-orchestrator-1
```

You should see:
- ✅ Embedding API calls succeed
- ✅ `vectorStore.similaritySearch()` works
- ❌ No `400 Bad Request` or `model not supported` errors
