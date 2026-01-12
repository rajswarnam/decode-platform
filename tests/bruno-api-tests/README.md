# Decode.AI API Test Collection (Bruno)

This collection contains API tests for the Decode.AI platform.

## Setup

1. **Install Bruno**: https://www.usebruno.com/
2. **Open Collection**: File → Open Collection → Select `bruno-api-tests/` folder
3. **Ensure Services Running**:
   ```bash
   docker-compose up -d llm-gateway-service
   ```

## Available Tests

### 1. LLM Gateway - Chat Completion
**Endpoint**: `POST http://localhost:8081/v1/chat/completions`

Tests the Azure OpenAI Gateway with token governance.

**Expected Response**:
```json
{
  "id": "chatcmpl-...",
  "object": "chat.completion",
  "model": "gpt-4o-mini-decode",
  "choices": [{
    "index": 0,
    "message": {
      "role": "assistant",
      "content": "customerAccount|Customer Account|A class representing..."
    },
    "finish_reason": "stop"
  }],
  "usage": {
    "prompt_tokens": 45,
    "completion_tokens": 20,
    "total_tokens": 65
  }
}
```

## Environment Variables

If needed, create `environments/local.bru`:

```
vars {
  baseUrl: http://localhost:8081
  postgresHost: host.docker.internal
  qdrantUrl: http://localhost:6333
}
```

## Full Pipeline Test

For end-to-end testing beyond just APIs, run:

```bash
./test-e2e-pipeline.sh
```

This will:
1. Create sample code files
2. Parse with Tree-sitter
3. Generate embeddings
4. Create semantic mappings via LLM Gateway
5. Verify results in database

## Troubleshooting

**Gateway returns 500**:
- Check `.env` file has Azure credentials
- Verify deployment name: `gpt-4o-mini-decode`

**No response**:
- Ensure gateway is running: `docker ps | grep llm-gateway`
- Check logs: `docker logs decode-workspace-llm-gateway-service-1`

**Token limit errors**:
- The gateway enforces 200k TPM
- Wait 60 seconds between large batches
