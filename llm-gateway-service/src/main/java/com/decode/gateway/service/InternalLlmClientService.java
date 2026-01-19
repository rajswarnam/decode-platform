package com.decode.gateway.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class InternalLlmClientService {

    private final RestTemplate restTemplate;
    private final AzureAdTokenService azureAdTokenService;

    @Value("${internal.llm.gateway.base-url}")
    private String baseUrl;

    @Value("${internal.llm.gateway.query-params:?api-version=2023-12-01-preview}")
    private String queryParams;

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
                String endpoint = baseUrl + "/chat/completions" + queryParams;

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.setBearerAuth(accessToken);

                Map<String, Object> requestBody = new HashMap<>();
                requestBody.put("model", model != null ? model : defaultModel);
                requestBody.put("stream", true);
                requestBody.put("temperature", 0);
                requestBody.put("top_p", 1);
                requestBody.put("max_tokens", 500);

                List<Map<String, String>> messages = new ArrayList<>();
                Map<String, String> userMsg = new HashMap<>();
                userMsg.put("role", "user");
                userMsg.put("content", userMessage);
                messages.add(userMsg);
                requestBody.put("messages", messages);

                HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

                restTemplate.execute(
                    endpoint,
                    HttpMethod.POST,
                    req -> {
                        req.getHeaders().addAll(headers);
                        // Write request body
                        ObjectMapper mapper = new ObjectMapper();
                        byte[] json = mapper.writeValueAsBytes(requestBody);
                        req.getBody().write(json);
                    },
                    response -> {
                        // Check HTTP status code first
                        HttpStatusCode statusCode = response.getStatusCode();
                        if (!statusCode.is2xxSuccessful()) {
                            log.error("Internal gateway returned error status: {} {}", statusCode.value(), statusCode);
                            try {
                                // Try to read error message from response body
                                String errorBody = new String(response.getBody().readAllBytes());
                                log.error("Error response body: {}", errorBody);
                                sink.next(createErrorChunk("Gateway error " + statusCode.value() + ": " + errorBody));
                            } catch (Exception e) {
                                sink.next(createErrorChunk("Gateway error " + statusCode.value()));
                            }
                            // Send final chunk to signal completion
                            sink.next(createFinalChunk());
                            return null;
                        }
                        
                        try (Scanner scanner = new Scanner(response.getBody())) {
                            boolean hasContent = false;
                            while (scanner.hasNextLine()) {
                                String line = scanner.nextLine();
                                if (line.startsWith("data:")) {
                                    String data = line.substring(5).trim();
                                    if (!data.isEmpty() && !"[DONE]".equals(data)) {
                                        // Validate that data is valid JSON before sending
                                        try {
                                            ObjectMapper mapper = new ObjectMapper();
                                            mapper.readTree(data);
                                            sink.next(data);
                                            hasContent = true;
                                        } catch (Exception e) {
                                            log.warn("Invalid JSON in streaming response: {}", data);
                                            // Send as error chunk instead
                                            sink.next(createErrorChunk("Invalid response format: " + data));
                                            sink.next(createFinalChunk());
                                            hasContent = true;
                                        }
                                    } else if ("[DONE]".equals(data)) {
                                        break;
                                    }
                                }
                            }
                            // If no content was received, send an error chunk followed by final chunk
                            if (!hasContent) {
                                log.warn("No content received from internal gateway streaming response");
                                sink.next(createErrorChunk("No content received from gateway"));
                                // Send final chunk with finish_reason to signal completion
                                sink.next(createFinalChunk());
                            }
                        } catch (Exception e) {
                            log.error("Error reading streaming response", e);
                            sink.next(createErrorChunk("Error reading response: " + e.getMessage()));
                            // Send final chunk to signal completion
                            sink.next(createFinalChunk());
                        }
                        return null;
                    }
                );
                sink.complete();
            } catch (Exception e) {
                log.error("Error calling internal LLM gateway (streaming)", e);
                // Send error as a chunk followed by final chunk instead of erroring the Flux
                sink.next(createErrorChunk("Error calling gateway: " + e.getMessage()));
                sink.next(createFinalChunk());
                sink.complete();
            }
        });
    }

    /**
     * Create an error chunk in OpenAI streaming format.
     * Returns both delta chunk and final message chunk.
     */
    private String createErrorChunk(String errorMessage) {
        try {
            // First create delta chunk with error content
            Map<String, Object> delta = new HashMap<>();
            delta.put("content", "Error: " + errorMessage);
            delta.put("role", "assistant"); // Add role to delta

            Map<String, Object> choice = new HashMap<>();
            choice.put("index", 0);
            choice.put("delta", delta);
            choice.put("finish_reason", null);

            Map<String, Object> chunk = new HashMap<>();
            String chunkId = "error-" + UUID.randomUUID();
            chunk.put("id", chunkId);
            chunk.put("object", "chat.completion.chunk");
            chunk.put("created", System.currentTimeMillis() / 1000);
            chunk.put("model", "error");
            chunk.put("choices", Collections.singletonList(choice));

            ObjectMapper mapper = new ObjectMapper();
            return mapper.writeValueAsString(chunk);
        } catch (Exception e) {
            log.error("Error creating error chunk", e);
            // Return minimal valid chunk format
            return "{\"id\":\"error\",\"object\":\"chat.completion.chunk\",\"created\":" 
                + (System.currentTimeMillis() / 1000) 
                + ",\"model\":\"error\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Error: " 
                + errorMessage.replace("\"", "\\\"").replace("\n", "\\n") 
                + "\",\"role\":\"assistant\"},\"finish_reason\":null}]}";
        }
    }

    /**
     * Non-streaming completion.
     */
    private Flux<String> streamCompletionNonStreaming(String userMessage, String model) {
        return Flux.create(sink -> {
            try {
                String accessToken = azureAdTokenService.getAccessToken();
                
                String endpoint = baseUrl + "/chat/completions" + queryParams;
                
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.setBearerAuth(accessToken);
                
                Map<String, Object> requestBody = new HashMap<>();
                requestBody.put("model", model != null ? model : defaultModel);
                requestBody.put("stream", false);
                requestBody.put("temperature", 0);
                requestBody.put("top_p", 1);
                requestBody.put("max_tokens", 500);

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
                    
                    // Always ensure content is not null
                    if (content == null || content.isEmpty()) {
                        log.warn("Extracted null or empty content from gateway response: {}", body);
                        content = "Error: Could not extract content from gateway response";
                    }
                    
                    sink.next(content);
                    sink.complete();
                } else {
                    log.error("Internal LLM gateway returned status: {} with body: {}", 
                            response.getStatusCode(), response.getBody());
                    // Return error message instead of erroring the Flux
                    sink.next("Error: Internal LLM gateway returned status " + response.getStatusCode());
                    sink.complete();
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
            // Check for error response first
            if (response.containsKey("error")) {
                Object error = response.get("error");
                if (error instanceof Map) {
                    Object message = ((Map<?, ?>) error).get("message");
                    log.error("Gateway returned error: {}", message);
                    return "Error: " + (message != null ? message.toString() : "Unknown error from gateway");
                }
                log.error("Gateway returned error: {}", error);
                return "Error: " + error.toString();
            }
            
            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
            if (choices != null && !choices.isEmpty()) {
                Map<String, Object> choice = choices.get(0);
                if (choice == null) {
                    log.warn("First choice is null in response: {}", response);
                    return "Error: Choice is null in gateway response";
                }
                
                Map<String, Object> message = (Map<String, Object>) choice.get("message");
                if (message == null) {
                    log.warn("Message is null in choice: {}", choice);
                    return "Error: Message is null in gateway response";
                }
                
                Object content = message.get("content");
                if (content == null) {
                    log.warn("Content is null in message: {}", message);
                    return "Error: Content is null in gateway response";
                }
                
                return content.toString();
            } else {
                log.warn("No choices found in response: {}", response);
                return "Error: No choices in gateway response";
            }
        } catch (Exception e) {
            log.error("Error extracting content from response: {}", response, e);
            return "Error: Failed to parse gateway response: " + e.getMessage();
        }
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