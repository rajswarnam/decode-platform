# Deploy Fix for NullPointerException in Chat Completion

## What Was Fixed

Fixed `NullPointerException` in `OpenAiChatModel` when processing LLM responses:
- Enhanced error handling in streaming endpoint
- Added error chunk formatting for SSE responses
- Improved non-streaming endpoint error handling

## Files Changed

- `llm-gateway-service/src/main/java/com/decode/gateway/controller/LlmController.java`
- `llm-gateway-service/src/main/java/com/decode/gateway/service/InternalLlmClientService.java`

## Step-by-Step Deployment

### 1. Pull Latest Changes

```bash
cd /path/to/your/decode-workspace
git pull origin spring-ai-fix
```

### 2. Rebuild llm-gateway-service

```bash
docker-compose build llm-gateway-service
```

**Note:** Only `llm-gateway-service` needs rebuilding. `context-orchestrator` was not changed.

### 3. Restart Services

```bash
# Restart the gateway service
docker-compose restart llm-gateway-service

# Restart context-orchestrator to pick up the fixed gateway
docker-compose restart context-orchestrator
```

### 4. Verify Deployment

```bash
# Check that services are running
docker ps | grep -E "llm-gateway-service|context-orchestrator"

# Check logs for errors
docker logs -f decode-platform-llm-gateway-service-1
docker logs -f decode-platform-context-orchestrator-1
```

### 5. Test

1. Open the UI and try running a prompt
2. Check logs for any `NullPointerException` errors
3. Verify that even if the internal gateway errors, the application continues running

## Quick One-Liner

```bash
git pull origin spring-ai-fix && \
docker-compose build llm-gateway-service && \
docker-compose restart llm-gateway-service context-orchestrator
```

## Expected Results

✅ **Before Fix:**
- `NullPointerException` crashes the application
- Error: `Cannot invoke "org.springframework.ai.openai.api.OpenAiApi$ChatCompletionMessage.role()" because the return value of "org.springframework.ai.openai.api.OpenAiApi$ChatCompletion$Choice.message()" is null`

✅ **After Fix:**
- Error messages are returned as valid responses
- Application continues running
- No `NullPointerException` crashes

## Troubleshooting

If you still see errors:

1. **Check if changes were pulled:**
   ```bash
   git log --oneline -5
   ```
   You should see: `fix: Handle errors in streaming endpoint to prevent NullPointerException`

2. **Verify the build:**
   ```bash
   docker-compose build llm-gateway-service --no-cache
   ```

3. **Check service connectivity:**
   ```bash
   # Test if gateway is accessible
   curl http://localhost:8081/health
   
   # Check if context-orchestrator can reach gateway
   docker exec decode-platform-context-orchestrator-1 curl http://llm-gateway-service:8081/health
   ```

4. **Check internal gateway access:**
   - Verify Azure AD token is being obtained
   - Check if internal LLM gateway is accessible from your network
   - Review `llm-gateway-service` logs for authentication errors

## Rollback (If Needed)

If the fix causes issues, you can rollback:

```bash
git checkout ca4898c  # Previous commit before streaming fix
docker-compose build llm-gateway-service
docker-compose restart llm-gateway-service context-orchestrator
```
