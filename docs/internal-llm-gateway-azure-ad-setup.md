# Internal LLM Gateway with Azure AD Authentication - Setup Instructions

This document provides step-by-step instructions for configuring the LLM gateway service to use an internal LLM gateway with Azure AD (Microsoft Entra) authentication.

## Architecture Overview

**Current Flow:**
```
context-orchestrator → llm-gateway-service → Azure OpenAI (direct)
```

**New Flow:**
```
context-orchestrator → llm-gateway-service → Azure AD (get token) → Internal LLM Gateway (with bearer token)
```

## Prerequisites

Before starting, you'll need:
1. **Azure AD Application Registration** credentials:
   - Client ID
   - Client Secret (or certificate)
   - Tenant ID
   
2. **Internal LLM Gateway** endpoint URL:
   - Base URL of your internal gateway (e.g., `https://internal-gateway.company.com/v1`)

3. **Environment variables** or configuration properties file

---

## Step 1: Update Maven Dependencies (pom.xml)

**File**: `llm-gateway-service/pom.xml`

**Actions:**

1. **Remove** the Spring AI Azure OpenAI dependency:
   ```xml
   <!-- REMOVE THIS: -->
   <dependency>
       <groupId>org.springframework.ai</groupId>
       <artifactId>spring-ai-azure-openai-spring-boot-starter</artifactId>
   </dependency>
   ```

2. **Add** these dependencies for Azure AD OAuth2 and HTTP client:
   ```xml
   <!-- Azure AD OAuth2 Client -->
   <dependency>
       <groupId>org.springframework.boot</groupId>
       <artifactId>spring-boot-starter-oauth2-client</artifactId>
   </dependency>
   
   <!-- Spring WebClient for HTTP calls -->
   <dependency>
       <groupId>org.springframework.boot</groupId>
       <artifactId>spring-boot-starter-webflux</artifactId>
   </dependency>
   
   <!-- OR if you prefer RestTemplate: -->
   <!-- Keep spring-boot-starter-web (already present) -->
   ```

3. **Keep** existing dependencies:
   - `spring-boot-starter-web` (or replace with `webflux` if using WebClient)
   - `reactor-core` (already present)
   - `lombok` (already present)
   - `jtokkit` (for TokenGovernor)

---

## Step 2: Create Azure AD Token Service

**File**: `llm-gateway-service/src/main/java/com/decode/gateway/service/AzureAdTokenService.java` (NEW FILE)

**Create this class:**

```java
package com.decode.gateway.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

@Service
@RequiredArgsConstructor
@Slf4j
public class AzureAdTokenService {

    private final RestTemplate restTemplate;

    @Value("${azure.ad.tenant-id}")
    private String tenantId;

    @Value("${azure.ad.client-id}")
    private String clientId;

    @Value("${azure.ad.client-secret}")
    private String clientSecret;

    @Value("${azure.ad.scope:https://graph.microsoft.com/.default}")
    private String scope;

    private String cachedAccessToken;
    private Instant tokenExpiryTime;
    private final ReentrantLock tokenLock = new ReentrantLock();

    /**
     * Get access token from Azure AD using OAuth2 Client Credentials flow.
     * Implements token caching to avoid unnecessary requests.
     */
    public String getAccessToken() {
        tokenLock.lock();
        try {
            // Check if cached token is still valid (with 5-minute buffer)
            if (cachedAccessToken != null && tokenExpiryTime != null 
                    && Instant.now().isBefore(tokenExpiryTime.minusSeconds(300))) {
                log.debug("Using cached Azure AD access token");
                return cachedAccessToken;
            }

            // Request new token
            log.info("Requesting new access token from Azure AD");
            String tokenEndpoint = String.format(
                "https://login.microsoftonline.com/%s/oauth2/v2.0/token", 
                tenantId
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("client_id", clientId);
            body.add("client_secret", clientSecret);
            body.add("scope", scope);
            body.add("grant_type", "client_credentials");

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);

            ResponseEntity<TokenResponse> response = restTemplate.postForEntity(
                tokenEndpoint,
                request,
                TokenResponse.class
            );

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                TokenResponse tokenResponse = response.getBody();
                cachedAccessToken = tokenResponse.getAccessToken();
                
                // Calculate expiry time (default to 3600 seconds if expires_in not provided)
                int expiresIn = tokenResponse.getExpiresIn() != null ? tokenResponse.getExpiresIn() : 3600;
                tokenExpiryTime = Instant.now().plusSeconds(expiresIn);
                
                log.info("Successfully obtained Azure AD access token (expires in {} seconds)", expiresIn);
                return cachedAccessToken;
            } else {
                throw new RuntimeException("Failed to obtain Azure AD access token: " + response.getStatusCode());
            }
        } catch (Exception e) {
            log.error("Error obtaining Azure AD access token", e);
            throw new RuntimeException("Failed to obtain Azure AD access token", e);
        } finally {
            tokenLock.unlock();
        }
    }

    /**
     * Clear cached token (useful for testing or forced refresh).
     */
    public void clearCachedToken() {
        tokenLock.lock();
        try {
            cachedAccessToken = null;
            tokenExpiryTime = null;
            log.debug("Cleared cached Azure AD access token");
        } finally {
            tokenLock.unlock();
        }
    }

    @Data
    private static class TokenResponse {
        @JsonProperty("access_token")
        private String accessToken;

        @JsonProperty("token_type")
        private String tokenType;

        @JsonProperty("expires_in")
        private Integer expiresIn;

        @JsonProperty("scope")
        private String scope;
    }
}
```

---

## Step 3: Create Internal LLM Client Service

**File**: `llm-gateway-service/src/main/java/com/decode/gateway/service/InternalLlmClientService.java` (NEW FILE)

**Create this class to handle calls to your internal LLM gateway:**

```java
package com.decode.gateway.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sink;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class InternalLlmClientService {

    private final RestTemplate restTemplate;
    private final AzureAdTokenService azureAdTokenService;

    @Value("${internal.llm.gateway.base-url}")
    private String baseUrl;

    @Value("${internal.llm.gateway.model:gpt-4o}")
    private String defaultModel;

    /**
     * Call internal LLM gateway for streaming response.
     * Returns Flux of response chunks.
     */
    public Flux<String> streamCompletion(String userMessage, String model, boolean stream) {
        if (stream) {
            return streamCompletionStreaming(userMessage, model);
        } else {
            return streamCompletionNonStreaming(userMessage, model);
        }
    }

    /**
     * Streaming completion (Server-Sent Events).
     */
    private Flux<String> streamCompletionStreaming(String userMessage, String model) {
        return Flux.create(sink -> {
            try {
                String accessToken = azureAdTokenService.getAccessToken();
                
                String endpoint = baseUrl + "/chat/completions";
                
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.setBearerAuth(accessToken);
                
                Map<String, Object> requestBody = new HashMap<>();
                requestBody.put("model", model != null ? model : defaultModel);
                requestBody.put("stream", true);
                
                List<Map<String, String>> messages = new ArrayList<>();
                Map<String, String> userMsg = new HashMap<>();
                userMsg.put("role", "user");
                userMsg.put("content", userMessage);
                messages.add(userMsg);
                requestBody.put("messages", messages);

                HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

                // For streaming, you'll need to handle SSE (Server-Sent Events)
                // This is a simplified version - you may need to adjust based on your internal gateway's SSE format
                restTemplate.execute(
                    endpoint,
                    HttpMethod.POST,
                    request -> {
                        request.getHeaders().addAll(headers);
                        // Write request body
                    },
                    response -> {
                        // Read SSE stream from response
                        // Parse and emit chunks via sink
                        // This part depends on your internal gateway's SSE format
                        return null;
                    }
                );

                sink.complete();
            } catch (Exception e) {
                log.error("Error calling internal LLM gateway (streaming)", e);
                sink.error(e);
            }
        });
    }

    /**
     * Non-streaming completion.
     */
    private Flux<String> streamCompletionNonStreaming(String userMessage, String model) {
        return Flux.create(sink -> {
            try {
                String accessToken = azureAdTokenService.getAccessToken();
                
                String endpoint = baseUrl + "/chat/completions";
                
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.setBearerAuth(accessToken);
                
                Map<String, Object> requestBody = new HashMap<>();
                requestBody.put("model", model != null ? model : defaultModel);
                requestBody.put("stream", false);
                
                List<Map<String, String>> messages = new ArrayList<>();
                Map<String, String> userMsg = new HashMap<>();
                userMsg.put("role", "user");
                userMsg.put("content", userMessage);
                messages.add(userMsg);
                requestBody.put("messages", messages);

                HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

                ResponseEntity<Map> response = restTemplate.exchange(
                    endpoint,
                    HttpMethod.POST,
                    request,
                    Map.class
                );

                if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                    // Extract content from response
                    Map<String, Object> body = response.getBody();
                    // Adjust based on your internal gateway's response format
                    String content = extractContentFromResponse(body);
                    
                    sink.next(content);
                    sink.complete();
                } else {
                    sink.error(new RuntimeException("Internal LLM gateway returned: " + response.getStatusCode()));
                }
            } catch (Exception e) {
                log.error("Error calling internal LLM gateway (non-streaming)", e);
                sink.error(e);
            }
        });
    }

    /**
     * Extract content from internal LLM gateway response.
     * Adjust this method based on your internal gateway's response format.
     */
    @SuppressWarnings("unchecked")
    private String extractContentFromResponse(Map<String, Object> response) {
        try {
            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
            if (choices != null && !choices.isEmpty()) {
                Map<String, Object> choice = choices.get(0);
                Map<String, Object> message = (Map<String, Object>) choice.get("message");
                if (message != null) {
                    return (String) message.get("content");
                }
            }
        } catch (Exception e) {
            log.warn("Error extracting content from response", e);
        }
        return "";
    }

    /**
     * Helper method to create chat completion request.
     * Adjust based on your internal gateway's API format.
     */
    public Map<String, Object> createCompletionRequest(String userMessage, String model, boolean stream) {
        Map<String, Object> request = new HashMap<>();
        request.put("model", model != null ? model : defaultModel);
        request.put("stream", stream);
        
        List<Map<String, String>> messages = new ArrayList<>();
        Map<String, String> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);
        messages.add(userMsg);
        request.put("messages", messages);
        
        return request;
    }
}
```

**Note:** The internal LLM client implementation depends on your internal gateway's exact API format (response structure, SSE format, etc.). Adjust the `extractContentFromResponse` and streaming methods accordingly.

---

## Step 4: Update LlmController

**File**: `llm-gateway-service/src/main/java/com/decode/gateway/controller/LlmController.java`

**Replace the entire class with this updated version:**

**Complete Updated LlmController Class:**

```java
package com.decode.gateway.controller;

import com.decode.gateway.dto.ChatRequest;
import com.decode.gateway.service.TokenGovernor;
import com.decode.gateway.service.InternalLlmClientService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/chat")
@RequiredArgsConstructor
@Slf4j
public class LlmController {

    // REMOVED: private final ChatClient.Builder chatClientBuilder;
    private final InternalLlmClientService internalLlmClientService;
    private final TokenGovernor tokenGovernor;
    private final ObjectMapper objectMapper;

    @PostMapping(value = "/completions", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> completions(@RequestBody ChatRequest request) {
        log.info("Gateway streaming request: model={} stream={}", request.getModel(), request.isStream());

        String userMessage = request.getMessages().stream()
                .filter(m -> "user".equals(m.getRole()))
                .map(ChatRequest.Message::getContent)
                .reduce((first, second) -> second)
                .orElse("");

        tokenGovernor.acquireTokenBudget(userMessage);

        if (request.isStream()) {
            // ========== STREAMING PATH ==========
            String requestId = "chatcmpl-" + UUID.randomUUID().toString();
            long created = System.currentTimeMillis() / 1000;

            // Call internal gateway - returns Flux<String> (content chunks)
            return internalLlmClientService.streamCompletion(userMessage, request.getModel(), true)
                    .map(content -> {
                        try {
                            // Transform content chunk to SSE format
                            Map<String, Object> delta = new HashMap<>();
                            delta.put("content", content != null ? content : "");

                            Map<String, Object> choice = new HashMap<>();
                            choice.put("index", 0);
                            choice.put("delta", delta);
                            choice.put("finish_reason", null);

                            Map<String, Object> chunk = new HashMap<>();
                            chunk.put("id", requestId);
                            chunk.put("object", "chat.completion.chunk");
                            chunk.put("created", created);
                            chunk.put("model", request.getModel());
                            chunk.put("choices", Collections.singletonList(choice));

                            return ServerSentEvent
                                    .builder(objectMapper.writeValueAsString(chunk))
                                    .build();
                        } catch (Exception e) {
                            log.error("Error creating chunk", e);
                            return ServerSentEvent.<String>builder()
                                    .comment("error: " + e.getMessage()).build();
                        }
                    })
                    .concatWith(Flux.just(ServerSentEvent.builder("[DONE]").build()));
        } else {
            // ========== NON-STREAMING PATH ==========
            // Call internal gateway - returns Flux<String>, get first (only) result
            String content = internalLlmClientService.streamCompletion(
                    userMessage, 
                    request.getModel(), 
                    false
            ).blockFirst(); // Get single result (non-streaming)
            
            try {
                // Format complete response as SSE
                Map<String, Object> message = new HashMap<>();
                message.put("role", "assistant");
                message.put("content", content != null ? content : "");

                Map<String, Object> choice = new HashMap<>();
                choice.put("index", 0);
                choice.put("message", message);
                choice.put("finish_reason", "stop");

                Map<String, Object> result = new HashMap<>();
                result.put("id", "chatcmpl-" + UUID.randomUUID());
                result.put("object", "chat.completion");
                result.put("created", System.currentTimeMillis() / 1000);
                result.put("model", request.getModel());
                result.put("choices", Collections.singletonList(choice));

                return Flux.just(ServerSentEvent.builder(objectMapper.writeValueAsString(result))
                        .build());
            } catch (Exception e) {
                log.error("Error processing non-streaming response", e);
                return Flux.just(ServerSentEvent.<String>builder()
                        .data("{\"error\": \"" + e.getMessage() + "\"}").build());
            }
        }
    }
}
```

**Key Changes:**
1. ❌ **REMOVED:** `private final ChatClient.Builder chatClientBuilder;`
2. ✅ **ADDED:** `private final InternalLlmClientService internalLlmClientService;`
3. ✅ **Streaming path (if block):** Replaced `chatClientBuilder.build().prompt().stream()` with `internalLlmClientService.streamCompletion(..., true).map(...)`
4. ✅ **Non-streaming path (else block):** Replaced `chatClientBuilder.build().prompt().call()` with `internalLlmClientService.streamCompletion(..., false).blockFirst()`
5. ✅ **KEPT:** All SSE formatting logic (both paths)

**Note:** The `InternalLlmClientService.streamCompletion()` method should return `Flux<String>` (content text chunks), and this controller handles the SSE formatting transformation.

---

## Step 5: Update Configuration File

**File**: `llm-gateway-service/src/main/resources/application.yaml`

**Remove** the Azure OpenAI configuration:
```yaml
# REMOVE THIS SECTION:
spring:
  ai:
    azure:
      openai:
        api-key: ${AZURE_OPENAI_KEY}
        endpoint: ${AZURE_OPENAI_ENDPOINT}
        deployment-name: ${AZURE_OPENAI_DEPLOYMENT}
        chat:
          options:
            deployment-name: ${AZURE_OPENAI_DEPLOYMENT}
            temperature: 0.7
```

**Add** Azure AD and internal LLM gateway configuration:
```yaml
spring:
  application:
    name: llm-gateway-service

# Azure AD Authentication
azure:
  ad:
    tenant-id: ${AZURE_AD_TENANT_ID}
    client-id: ${AZURE_AD_CLIENT_ID}
    client-secret: ${AZURE_AD_CLIENT_SECRET}
    scope: ${AZURE_AD_SCOPE:https://graph.microsoft.com/.default}  # Default scope, adjust if needed

# Internal LLM Gateway
internal:
  llm:
    gateway:
      base-url: ${INTERNAL_LLM_GATEWAY_BASE_URL}  # e.g., https://internal-gateway.company.com/v1
      model: ${INTERNAL_LLM_MODEL:gpt-4o}  # Default model name
```

**Keep** the server configuration:
```yaml
server:
  port: 8081
```

---

## Step 6: Add RestTemplate Bean Configuration

**File**: `llm-gateway-service/src/main/java/com/decode/gateway/config/RestTemplateConfig.java` (NEW FILE)

**Create RestTemplate bean for HTTP calls:**

```java
package com.decode.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);  // 5 seconds
        factory.setReadTimeout(30000);    // 30 seconds (adjust based on LLM response time)
        
        return new RestTemplate(factory);
    }
}
```

---

## Step 7: Update Docker Compose (Optional)

**File**: `docker-compose.yaml`

**Update environment variables** for `llm-gateway-service`:

```yaml
llm-gateway-service:
  build: ./llm-gateway-service
  ports: ["8081:8081"]
  env_file: .env
  environment:
    # Azure AD Configuration
    - AZURE_AD_TENANT_ID=${AZURE_AD_TENANT_ID}
    - AZURE_AD_CLIENT_ID=${AZURE_AD_CLIENT_ID}
    - AZURE_AD_CLIENT_SECRET=${AZURE_AD_CLIENT_SECRET}
    - AZURE_AD_SCOPE=${AZURE_AD_SCOPE:-https://graph.microsoft.com/.default}
    # Internal LLM Gateway Configuration
    - INTERNAL_LLM_GATEWAY_BASE_URL=${INTERNAL_LLM_GATEWAY_BASE_URL}
    - INTERNAL_LLM_MODEL=${INTERNAL_LLM_MODEL:-gpt-4o}
```

**Remove** old Azure OpenAI environment variables:
```yaml
# REMOVE THESE:
# - AZURE_OPENAI_KEY=${AZURE_OPENAI_KEY}
# - AZURE_OPENAI_ENDPOINT=${AZURE_OPENAI_ENDPOINT}
# - AZURE_OPENAI_DEPLOYMENT=${AZURE_OPENAI_DEPLOYMENT}
```

---

## Step 8: Update .env File (or Environment Variables)

**Create/Update** `.env` file in project root:

```bash
# Azure AD Authentication
AZURE_AD_TENANT_ID=your-tenant-id-here
AZURE_AD_CLIENT_ID=your-client-id-here
AZURE_AD_CLIENT_SECRET=your-client-secret-here
AZURE_AD_SCOPE=https://graph.microsoft.com/.default  # Adjust if needed

# Internal LLM Gateway
INTERNAL_LLM_GATEWAY_BASE_URL=https://internal-gateway.company.com/v1
INTERNAL_LLM_MODEL=gpt-4o  # Adjust based on your internal gateway's model names
```

**Important:** Never commit `.env` file to git! It's already in `.gitignore`.

---

## Step 9: Update context-orchestrator Configuration (No Changes Needed)

**File**: `context-orchestrator/src/main/resources/application.yaml`

**No changes needed** - it already calls `llm-gateway-service:8081`, which will now route to your internal gateway.

---

## Step 10: Testing

### Test Azure AD Token Service:

1. **Test token retrieval:**
   - Start `llm-gateway-service`
   - Check logs for "Successfully obtained Azure AD access token"
   - Verify no errors

2. **Test token caching:**
   - Make multiple calls
   - First call should show "Requesting new access token"
   - Subsequent calls should show "Using cached Azure AD access token"

### Test Internal LLM Gateway Call:

1. **Test non-streaming:**
   - Call `/v1/chat/completions` with `stream=false`
   - Verify response format matches expected structure

2. **Test streaming:**
   - Call `/v1/chat/completions` with `stream=true`
   - Verify SSE (Server-Sent Events) format matches

3. **Test from context-orchestrator:**
   - Run a full analysis request
   - Verify LLM responses work end-to-end

---

## Troubleshooting

### Issue: "Failed to obtain Azure AD access token"

**Solutions:**
- Verify `AZURE_AD_TENANT_ID`, `AZURE_AD_CLIENT_ID`, `AZURE_AD_CLIENT_SECRET` are correct
- Check Azure AD app registration has correct permissions
- Verify the scope is correct for your internal gateway
- Check network connectivity to `login.microsoftonline.com`

### Issue: "Internal LLM gateway returned 401 Unauthorized"

**Solutions:**
- Verify access token is being included in Authorization header as `Bearer <token>`
- Check token hasn't expired
- Verify the token scope is correct for your internal gateway
- Check internal gateway accepts Azure AD tokens

### Issue: "Internal LLM gateway returned 403 Forbidden"

**Solutions:**
- Verify Azure AD app has correct permissions/roles
- Check internal gateway has your client ID whitelisted
- Verify scope includes required permissions

### Issue: "Response format doesn't match"

**Solutions:**
- Adjust `extractContentFromResponse()` method based on actual response format
- Check internal gateway's API documentation
- Log response body to see actual format

---

## Important Notes

1. **Token Caching:** The `AzureAdTokenService` implements token caching with automatic refresh. Tokens are refreshed 5 minutes before expiry.

2. **Error Handling:** Add retry logic for transient failures (network issues, temporary 500 errors).

3. **Security:** Never commit `.env` files or secrets to git. Use environment variables or secure secret management in production.

4. **Scope Configuration:** The default scope is `https://graph.microsoft.com/.default`. Adjust this based on what your internal LLM gateway requires.

5. **API Format:** Adjust the internal LLM client service methods based on your internal gateway's exact API format (request/response structure, SSE format, etc.).

6. **Streaming Implementation:** The streaming implementation in `InternalLlmClientService` is a placeholder. You'll need to implement SSE parsing based on your internal gateway's format (e.g., parse `data: {...}` chunks).

---

## Summary Checklist

- [ ] Update `pom.xml` - remove Azure OpenAI dependency, add OAuth2 client
- [ ] Create `AzureAdTokenService.java` - token retrieval and caching
- [ ] Create `InternalLlmClientService.java` - calls to internal LLM gateway
- [ ] Update `LlmController.java` - use new internal client service
- [ ] Update `application.yaml` - Azure AD and internal gateway config
- [ ] Create `RestTemplateConfig.java` - HTTP client configuration
- [ ] Update `docker-compose.yaml` - environment variables
- [ ] Update `.env` file - Azure AD credentials and internal gateway URL
- [ ] Test token retrieval
- [ ] Test internal LLM gateway calls
- [ ] Test end-to-end from context-orchestrator

---

This completes the setup instructions. Follow these steps in your new workspace to configure the internal LLM gateway with Azure AD authentication.