# Fix: EmbeddingModel Bean Missing Error

## Error

```
UnsatisfiedDependencyException: Error creating bean with name 'vectorStore'
Consider defining a bean of type 'org.springframework.ai.embedding.EmbeddingModel' in your configuration.
```

## Root Cause

The `vectorStore` bean (Qdrant) requires an `EmbeddingModel` to function. When you perform similarity search, Spring AI needs to embed the query text to search for similar documents.

We removed the transformer embeddings, so there's no `EmbeddingModel` bean available.

## Why VectorStore Needs EmbeddingModel

- **`context-orchestrator` uses `vectorStore`** for similarity search (querying embeddings)
- **When you call `vectorStore.similaritySearch()`**, Spring AI needs to:
  1. Embed the query text (requires `EmbeddingModel`)
  2. Search for similar embeddings in Qdrant
  3. Return matching documents

## Solution: Enable OpenAI Embeddings for Query Embedding

**Documents are embedded by `vectorizer-service`**, but **queries need to be embedded by `context-orchestrator`**.

Since we only embed queries (not documents), using OpenAI embeddings is lightweight and acceptable.

### Updated Configuration

**In `application.yaml`**:
```yaml
spring:
  ai:
    openai:
      embedding:
        enabled: true  # Enable for query embedding
    embedding:
      transformer:
        enabled: false  # Disable transformer embeddings
```

## Architecture Clarification

| Service | Purpose | Embeddings Usage |
|---------|---------|------------------|
| `context-orchestrator` | LLM chat, agent orchestration, **query similarity search** | ✅ Embeds **queries** only (lightweight) |
| `vectorizer-service` | Create embeddings for **documents**, store in Qdrant | ✅ Embeds **documents** (heavy) |

## Why This Works

- **Documents**: Embedded once by `vectorizer-service` and stored in Qdrant
- **Queries**: Embedded on-demand by `context-orchestrator` when performing similarity search
- **Lightweight**: Query embedding is cheap since it's only the query text, not entire documents
- **OpenAI embeddings**: Fast and reliable for query embedding

## Alternative: If You Don't Want to Use OpenAI for Queries

If you want to use local ONNX embeddings for queries too, you need to:

1. Keep `spring-ai-transformers-spring-boot-starter` dependency
2. Configure ONNX models properly
3. Enable transformer embeddings

But since query embedding is lightweight, OpenAI is fine.

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
- ✅ `vectorStore` bean created successfully
- ❌ No `UnsatisfiedDependencyException` for `EmbeddingModel`
