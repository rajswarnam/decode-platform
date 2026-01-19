# Fix: Use Local ONNX Embeddings Instead of Gateway

## Problem

Your internal gateway doesn't have embedding models, but `context-orchestrator` needs embeddings for similarity search in `vectorStore`.

## Solution: Use Local ONNX Embeddings

Use the local ONNX embeddings that are already downloaded and available in `./infra/models/`.

## Changes Made

### 1. Add Back Transformers Dependency

**In `pom.xml`**:
```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-transformers-spring-boot-starter</artifactId>
    <version>1.0.0-M6</version>
    <exclusions>
        <exclusion>
            <groupId>ai.djl.pytorch</groupId>
            <artifactId>pytorch-engine</artifactId>
        </exclusion>
        <exclusion>
            <groupId>ai.djl.pytorch</groupId>
            <artifactId>pytorch-native-auto</artifactId>
        </exclusion>
    </exclusions>
</dependency>
<dependency>
    <groupId>ai.djl.onnxruntime</groupId>
    <artifactId>onnxruntime-engine</artifactId>
    <version>0.32.0</version>
</dependency>
<dependency>
    <groupId>com.microsoft.onnxruntime</groupId>
    <artifactId>onnxruntime</artifactId>
    <version>1.21.1</version>
</dependency>
```

### 2. Enable Transformer Embeddings

**In `application.yaml`**:
```yaml
spring:
  ai:
    openai:
      embedding:
        enabled: false  # Disable OpenAI embeddings
    embedding:
      transformer:
        enabled: true  # Enable local ONNX embeddings
        onnx:
          model-uri: file:/models/model.onnx
          tokenizer-uri: file:/models/tokenizer.json
```

### 3. Docker Compose Already Configured

The `docker-compose.yaml` already has:
- Volume mount: `./infra/models:/models`
- Environment variables for ONNX Runtime

## Verify Models Exist

Make sure the ONNX models are in `./infra/models/`:

```bash
ls -la infra/models/
```

You should see:
- `model.onnx`
- `tokenizer.json`

If models don't exist, download them (see `docs/local-onnx-embedding-setup.md`).

## Architecture

| Service | Embeddings Source | Purpose |
|---------|-------------------|---------|
| `context-orchestrator` | **Local ONNX** (for queries) | Query embedding for similarity search |
| `vectorizer-service` | **Local ONNX** (for documents) | Document embedding for storage |

Both services now use the same local ONNX models, ensuring compatibility.

## Benefits

- ✅ **No gateway dependency** for embeddings
- ✅ **Local execution** - faster, no network calls
- ✅ **Consistent embeddings** - same model for queries and documents
- ✅ **Offline capable** - works without internet

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
- ✅ `TransformersEmbeddingModel` created successfully
- ✅ `vectorStore` bean created successfully
- ❌ No `400 Bad Request` or `model not supported` errors
