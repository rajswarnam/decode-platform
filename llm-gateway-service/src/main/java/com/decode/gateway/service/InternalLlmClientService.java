package com.decode.gateway.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import reactor.core.publisher.Flux;

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
                        try (Scanner scanner = new Scanner(response.getBody())) {
                            while (scanner.hasNextLine()) {
                                String line = scanner.nextLine();
                                if (line.startsWith("data:")) {
                                    String data = line.substring(5).trim();
                                    if (!data.isEmpty() && !"[DONE]".equals(data)) {
                                        sink.next(data);
                                    } else if ("[DONE]".equals(data)) {
                                        break;
                                    }
                                }
                            }
                        }
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