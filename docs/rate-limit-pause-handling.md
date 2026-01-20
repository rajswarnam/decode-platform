# Rate Limit Pause Handling

## Overview

The system is designed to handle rate limit pauses of up to 94-95 seconds and automatically resume processing afterward.

## How Rate Limiting Works

### 1. **Pause Happens BEFORE LLM Call**

The rate limit pause occurs in `TokenGovernor.acquireTokenBudget()`, which is called **before** making the LLM request:

```
Worker Thread → LLM Gateway → acquireTokenBudget() → [PAUSE 94s] → LLM Request → Response
```

**Key Point**: The pause happens **before** the HTTP request, so the worker thread is simply blocked during the pause. After the pause completes, the LLM call proceeds normally.

### 2. **Progress Updates Keep SSE Alive**

During the pause, progress updates are sent every 5 seconds:

```java
// In TokenGovernor.java
long chunkSize = 5000; // 5 seconds
while (remaining > 0) {
    sleep(sleepTime);
    // Send keep-alive every 5 seconds
    if (pauseProgressCallback != null && remaining > 0) {
        pauseProgressCallback.accept("⏳ Waiting for rate limit window reset... X seconds remaining");
    }
}
```

These progress updates:
- Keep the SSE connection alive
- Inform the UI about the pause
- Prevent timeouts during long pauses

### 3. **Automatic Resume**

After the pause completes:
1. Token bucket is reset
2. LLM request proceeds normally
3. Worker continues processing
4. Results are returned to the UI

**No manual intervention needed** - the system automatically resumes.

## Timeout Configuration

### HTTP Timeouts

**Current Settings:**
- **Read Timeout**: 600s (10 minutes) - `application.yaml` + `HttpTimeoutConfig`
- **Connect Timeout**: 60s
- **SSE Timeout**: 600s (10 minutes) - `HttpTimeoutConfig.configureAsyncSupport()`

**Why 600s?**
- Rate limit pause: up to 60s (full window reset)
- LLM call time: 30-60s per call
- Multiple workers: 4-8 workers × 60s = 240-480s
- Buffer: Additional time for processing
- **Total**: 60s pause + 480s workers + buffer = ~600s

### Previous Issue (Fixed)

**Before**: HTTP read-timeout was 120s
- Problem: 94s pause + 60s LLM call = 154s > 120s timeout
- Result: `InterruptedException` during pause
- **Fixed**: Increased to 600s to handle pauses + multiple LLM calls

## Worker Thread Behavior

### During Pause

1. Worker thread calls `blockingCall(prompt)`
2. This calls LLM gateway via HTTP
3. LLM gateway calls `acquireTokenBudget()`
4. If approaching limit, thread **blocks** for up to 60s
5. Progress updates sent every 5s (keeps SSE alive)
6. Thread is **not interrupted** - just waiting

### After Pause

1. Token bucket resets
2. `acquireTokenBudget()` returns
3. LLM HTTP request proceeds
4. Response received
5. Worker continues with results
6. Task marked as COMPLETED

## Verification

### Check Current Timeouts

```bash
# Check application.yaml
grep -A 2 "http:" context-orchestrator/src/main/resources/application.yaml

# Check HttpTimeoutConfig
cat context-orchestrator/src/main/java/com/decode/context/orchestrator/config/HttpTimeoutConfig.java
```

### Monitor Rate Limit Pauses

Watch logs for:
```
⏸️ TPM PAUSE THRESHOLD: X/250000 tokens (Y%) approaching limit. Pausing for Z ms...
⏳ Waiting for rate limit window reset... X seconds remaining
✅ Rate limit window reset. Resuming requests...
```

### Test Scenario

1. Trigger analysis that will hit rate limit
2. Observe pause messages in logs
3. Verify workers complete after pause
4. Check that results are returned to UI

## Troubleshooting

### Workers Still Getting InterruptedException

**Possible Causes:**
1. HTTP timeout still too short (check both `application.yaml` and `HttpTimeoutConfig`)
2. SSE connection timing out (check `configureAsyncSupport` timeout)
3. Client-side timeout (UI might be disconnecting)

**Solutions:**
1. Verify timeout is 600s in both places
2. Check SSE timeout configuration
3. Ensure UI keeps connection alive during pauses

### Pause Not Resuming

**Possible Causes:**
1. Thread interruption during pause
2. Exception in progress callback
3. Network issue during pause

**Solutions:**
1. Check logs for exceptions during pause
2. Verify progress callback is working
3. Check network connectivity

## Summary

✅ **System CAN handle 94-95 second pauses**
- Pause happens before LLM call (thread just blocks)
- Progress updates keep SSE alive
- Timeouts configured for 600s (10 minutes)
- Workers automatically resume after pause

✅ **Workers CAN resume processing**
- No interruption during pause
- LLM call proceeds after pause
- Results returned normally
- Task marked as COMPLETED

**Key Configuration:**
- HTTP read-timeout: 600s
- SSE timeout: 600s
- Progress updates: Every 5s during pause
