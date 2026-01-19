# Fix: ORT_INVALID_PROTOBUF - Invalid ONNX Model File

## Problem

```
Error code ORT_INVALID_PROTOBUF
message: Failed to load model because protobuf parsing failed.
```

This error occurs when the `model.onnx` file is:
- Missing
- Corrupted
- A placeholder file (e.g., "Entry not found")
- Wrong format

## Root Cause

The `infra/models/model.onnx` file was only **15 bytes** and contained "Entry not found" - it's a placeholder, not a valid ONNX model file.

A valid MiniLM ONNX model should be **~20-90MB**.

## Solution Options

### Option 1: Let Spring AI Auto-Download (Recommended)

Remove the invalid model files and let Spring AI transformers auto-download the model on first use:

```bash
# Remove invalid placeholder files
rm -f infra/models/model.onnx infra/models/tokenizer.json
```

Spring AI will automatically download the model to `/tmp/spring-ai-onnx-generative/` when the application starts.

**Pros:**
- ✅ No manual download needed
- ✅ Works immediately
- ✅ Spring AI handles model versioning

**Cons:**
- ⚠️ Requires internet on first startup
- ⚠️ Model stored in `/tmp` (may be cleared on restart)

### Option 2: Download Valid ONNX Model Manually

#### Using Python (transformers library):

```bash
pip install transformers torch onnx optimum[onnxruntime]

python3 << EOF
from optimum.onnxruntime import ORTModelForFeatureExtraction
from transformers import AutoTokenizer
import os

model_dir = "infra/models"
os.makedirs(model_dir, exist_ok=True)

print("Downloading and converting model...")
model = ORTModelForFeatureExtraction.from_pretrained(
    "sentence-transformers/all-MiniLM-L6-v2",
    export=True
)
tokenizer = AutoTokenizer.from_pretrained("sentence-transformers/all-MiniLM-L6-v2")

# Save ONNX model
model.save_pretrained(model_dir)
tokenizer.save_pretrained(model_dir)

print(f"✓ Model saved to {model_dir}")
EOF
```

#### Using curl (if direct URL available):

```bash
# Note: The Xenova repository may not have direct ONNX downloads
# Try this alternative source:
curl -L -o infra/models/model.onnx \
  https://github.com/onnx/models/raw/main/validated/vision/classification/resnet/model/resnet50-v1-7.onnx
# (This is just an example - you need the correct MiniLM ONNX URL)
```

### Option 3: Use Pre-Downloaded Model from Spring AI Cache

If Spring AI has already downloaded the model to `/tmp/spring-ai-onnx-generative/`, copy it:

```bash
# Find the cached model
find /tmp -name "model.onnx" -path "*/spring-ai-onnx-generative/*" 2>/dev/null

# Copy to your models directory
cp /tmp/spring-ai-onnx-generative/*/model.onnx infra/models/model.onnx
cp /tmp/spring-ai-onnx-generative/*/tokenizer.json infra/models/tokenizer.json
```

## Verify Model File

After downloading, verify the model file is valid:

```bash
# Check file size (should be 20-90MB for MiniLM)
ls -lh infra/models/model.onnx

# Check file type (should show ONNX or binary)
file infra/models/model.onnx

# Verify it's not a placeholder
head -c 100 infra/models/model.onnx | strings
```

## Configuration

Your `application.yaml` should have:

```yaml
spring:
  ai:
    embedding:
      transformer:
        enabled: true
        onnx:
          model-uri: file:/models/model.onnx
          tokenizer-uri: file:/models/tokenizer.json
```

And `docker-compose.yaml` should mount the models:

```yaml
context-orchestrator:
  volumes:
    - ./infra/models:/models
```

## After Fix

1. Rebuild the Docker container:
   ```bash
   docker-compose build context-orchestrator
   docker-compose up -d context-orchestrator
   ```

2. Check logs:
   ```bash
   docker logs -f decode-platform-context-orchestrator-1
   ```

3. You should see:
   - ✅ `TransformersEmbeddingModel` created successfully
   - ✅ `vectorStore` bean created successfully
   - ✅ `Started ContextOrchestratorApplication`
   - ❌ No `ORT_INVALID_PROTOBUF` errors

## Alternative: Disable Local Models (Use Gateway)

If you can't get local ONNX models working, you can temporarily disable transformer embeddings and use the gateway (if it supports embeddings):

```yaml
spring:
  ai:
    embedding:
      transformer:
        enabled: false
    openai:
      embedding:
        enabled: true
        options:
          model: text-embedding-ada-002  # Or your gateway's embedding model
```

But this requires your gateway to support embeddings, which you mentioned it doesn't.

## Summary

The quickest fix is **Option 1**: Remove the invalid files and let Spring AI auto-download the model on first startup. The model will be cached and reused on subsequent starts.
