# Troubleshooting: NullPointerException in Streaming Response

## Problem

Even after multiple fixes, the error persists:
```
java.lang.NullPointerException: Cannot invoke "org.springframework.ai.openai.api.OpenAiApi$ChatCompletionMessage.role()" 
because the return value of "org.springframework.ai.openai.api.OpenAiApi$ChatCompletion$Choice.message()" is null
```

The error occurs in `OpenAiChatModel.lambda$internalStream$6(OpenAiChatModel.java:387)`, which is the **streaming** path, even though logs show "non-streaming request".

## Root Cause Analysis

1. **Spring AI is calling `/completions` endpoint with `stream=true`** in the request body
2. **But it expects streaming responses** (SSE format with `delta` fields)
3. **When internal gateway errors**, our error chunks might not be properly formatted
4. **Or the internal gateway is returning a response** that doesn't match expected format

## Debugging Steps

### 1. Check What the Internal Gateway Returns

Add detailed logging to see the actual response:

```java
log.error("Internal gateway response status: {}", response.getStatusCode());
log.error("Internal gateway response body: {}", response.getBody());
log.error("Extracted content: {}", content);
```

### 2. Check What We're Returning to Spring AI

Add logging before returning response:

```java
log.debug("Returning response to Spring AI: {}", result);
log.debug("Choices: {}", result.get("choices"));
log.debug("First choice: {}", ((List<?>) result.get("choices")).get(0));
```

### 3. Verify Response Structure

Spring AI expects:
- **Non-streaming**: `{ choices: [{ message: { role: "assistant", content: "..." } }] }`
- **Streaming**: `data: { choices: [{ delta: { content: "..." } }] }`

### 4. Check Internal Gateway Response Format

The internal gateway might be returning:
- Error responses in a different format
- Empty/null choices
- Malformed JSON

## Possible Issues

1. **Internal gateway returns error** (403, 401, 500)
   - Our error handling might not be creating valid responses
   - Spring AI might be trying to parse error responses as valid chunks

2. **Response format mismatch**
   - Internal gateway might return format we don't expect
   - Our `extractContentFromResponse()` might not handle all cases

3. **Spring AI using streaming internally**
   - Even when calling non-streaming endpoint, Spring AI might use streaming
   - Our non-streaming endpoint might not handle `stream=true` correctly

## Next Steps

1. **Check internal gateway logs** for actual errors
2. **Add more detailed logging** to see exact response structure
3. **Verify Azure AD token** is valid and not expired
4. **Check if internal gateway is accessible** from Docker network
5. **Review internal gateway response format** documentation

## Temporary Workaround

If the internal gateway is not accessible:
- Mock the response with a valid structure
- Return a hardcoded error message in correct format
- Disable the problematic feature temporarily
