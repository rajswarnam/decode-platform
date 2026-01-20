# Why InterruptedException Occurs Despite Rate Limiting

## The Confusion

**Question**: "If we're handling rate limiting properly, why do we see `InterruptedException`?"

**Answer**: The `InterruptedException` is **NOT** caused by the rate limiting pause itself. It's caused by **HTTP timeouts** during the LLM call that happens **AFTER** the pause.

## The Flow

### What Happens During Rate Limiting

```
1. Worker calls blockingCall(prompt)
2. → LLM Gateway: acquireTokenBudget()
3. → [PAUSE 94s] ← Thread just sleeps here (NO interruption)
4. → Token bucket resets
5. → HTTP Request to LLM Gateway ← THIS is where timeout can occur
6. → Stream processing ← THIS can throw InterruptedException
```

### Why InterruptedException Occurs

The `InterruptedException` comes from **two different places**:

#### 1. **HTTP Timeout (Before Fix)**

**Before**: HTTP read-timeout was 120s
- Rate limit pause: 94s (thread sleeps, no interruption)
- After pause: HTTP request starts
- LLM call takes 30-60s
- **Total**: 94s + 60s = 154s > 120s timeout
- **Result**: HTTP connection times out
- **Spring AI stream processing**: When HTTP times out, the stream iterator throws `InterruptedException`

**After Fix**: HTTP read-timeout is 600s
- Rate limit pause: 94s (thread sleeps, no interruption)
- After pause: HTTP request starts
- LLM call takes 30-60s
- **Total**: 94s + 60s = 154s < 600s timeout
- **Result**: No timeout, no InterruptedException

#### 2. **Stream Processing Interruption**

Spring AI's `.stream().chatResponse().toIterable().forEach()` can throw `InterruptedException` if:
- The underlying HTTP connection is closed
- The stream is interrupted by another thread
- The client (UI) disconnects
- Network issues occur during streaming

## The Fix

### 1. Increased HTTP Timeout

**File**: `context-orchestrator/src/main/resources/application.yaml`
```yaml
spring:
  http:
    client:
      read-timeout: 600s  # Was 120s - now handles pause + LLM calls
```

**File**: `context-orchestrator/src/main/java/com/decode/context/orchestrator/config/HttpTimeoutConfig.java`
```java
setReadTimeout(600_000); // 10 Minutes
```

### 2. Enhanced InterruptedException Handling

**File**: `context-orchestrator/src/main/java/com/decode/context/orchestrator/agent/AgentOrchestrator.java`

```java
private String blockingCall(String promptContext) {
    try {
        chatClientBuilder.build()
            .prompt(promptContext)
            .stream()
            .chatResponse()
            .toIterable()
            .forEach(response -> {
                // Check for interruption before processing each chunk
                if (Thread.currentThread().isInterrupted()) {
                    return; // Exit early if interrupted
                }
                // ... process response
            });
    } catch (InterruptedException e) {
        // Thread was interrupted - restore interrupt status
        Thread.currentThread().interrupt();
        // Return partial results if available
        if (sb.length() > 0) {
            return sb.toString() + "\n\n[Note: Response was interrupted but partial results are available]";
        }
        return "Error: LLM call was interrupted. Please try again.";
    }
}
```

## Key Points

### Rate Limiting Pause Does NOT Cause InterruptedException

✅ **Rate limiting pause**:
- Happens BEFORE HTTP request
- Thread just sleeps (blocks)
- No interruption occurs
- Progress updates keep SSE alive

❌ **HTTP Timeout DOES Cause InterruptedException**:
- Happens DURING HTTP request
- Connection times out
- Stream processing throws InterruptedException
- This is what we fixed

### Why It Seemed Related

The timing made it seem related:
1. Rate limit pause: 94s
2. HTTP request starts
3. LLM call takes 60s
4. **Total: 154s > 120s timeout**
5. HTTP times out → InterruptedException

**It looked like the pause caused it, but actually the timeout after the pause caused it.**

## Verification

### Check if Timeout is Fixed

```bash
# Verify timeout is 600s
grep -A 2 "read-timeout" context-orchestrator/src/main/resources/application.yaml

# Should show: read-timeout: 600s
```

### Monitor for InterruptedException

After the fix, you should see:
- ✅ Rate limit pauses complete successfully
- ✅ LLM calls proceed after pause
- ✅ No InterruptedException (unless client disconnects)
- ✅ Workers complete successfully

If you still see InterruptedException:
1. Check if timeout is actually 600s (not 120s)
2. Check if client (UI) is disconnecting
3. Check network connectivity
4. Check if stream is being interrupted by another thread

## Summary

| Event | Causes InterruptedException? | Why |
|-------|----------------------------|-----|
| Rate limit pause | ❌ NO | Thread just sleeps, no interruption |
| HTTP timeout | ✅ YES | Connection times out, stream interrupted |
| Client disconnect | ✅ YES | Stream closed, iterator interrupted |
| Network issue | ✅ YES | Connection lost, stream interrupted |

**The fix**: Increased HTTP timeout to 600s so that pause (94s) + LLM call (60s) = 154s < 600s timeout, preventing HTTP timeouts and thus preventing InterruptedException.
