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

    // Streaming endpoint
    @PostMapping(value = "/completions/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> completionsStream(@RequestBody ChatRequest request) {
        log.info("Gateway streaming request: model={} stream={}", request.getModel(), request.isStream());

        String userMessage = request.getMessages().stream()
                .filter(m -> "user".equals(m.getRole()))
                .map(ChatRequest.Message::getContent)
                .reduce((first, second) -> second)
                .orElse("");

        tokenGovernor.acquireTokenBudget(userMessage);

        String requestId = "chatcmpl-" + UUID.randomUUID().toString();
        long created = System.currentTimeMillis() / 1000;

        // Call internal gateway - returns Flux<String> (content chunks)
        return internalLlmClientService.streamCompletion(userMessage, request.getModel(), true)
                .onErrorResume(error -> {
                    log.error("Error in streaming completion", error);
                    // Return error as a valid JSON chunk string instead of failing
                    Map<String, Object> delta = new HashMap<>();
                    delta.put("content", "Error: " + error.getMessage());

                    Map<String, Object> choice = new HashMap<>();
                    choice.put("index", 0);
                    choice.put("delta", delta);
                    choice.put("finish_reason", "stop");

                    Map<String, Object> chunk = new HashMap<>();
                    chunk.put("id", requestId);
                    chunk.put("object", "chat.completion.chunk");
                    chunk.put("created", created);
                    chunk.put("model", request.getModel() != null ? request.getModel() : "gpt-4o");
                    chunk.put("choices", Collections.singletonList(choice));

                    try {
                        // Return Flux<String> (JSON string), not Flux<ServerSentEvent>
                        return Flux.just(objectMapper.writeValueAsString(chunk));
                    } catch (Exception e) {
                        log.error("Error creating error chunk", e);
                        return Flux.just("{\"error\":\"" + error.getMessage().replace("\"", "\\\"") + "\"}");
                    }
                })
                .map(content -> {
                    try {
                        // If content is already a JSON string (error chunk), parse and return as SSE
                        if (content != null && content.trim().startsWith("{")) {
                            try {
                                // Try to parse as JSON to validate
                                objectMapper.readTree(content);
                                return ServerSentEvent.builder(content).build();
                            } catch (Exception e) {
                                // Not valid JSON, treat as plain content
                            }
                        }
                        
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
                        chunk.put("model", request.getModel() != null ? request.getModel() : "gpt-4o");
                        chunk.put("choices", Collections.singletonList(choice));

                        return ServerSentEvent
                                .builder(objectMapper.writeValueAsString(chunk))
                                .build();
                    } catch (Exception e) {
                        log.error("Error creating chunk", e);
                        // Return error as valid SSE chunk
                        Map<String, Object> errorChunk = new HashMap<>();
                        errorChunk.put("error", e.getMessage());
                        try {
                            return ServerSentEvent.builder(objectMapper.writeValueAsString(errorChunk)).build();
                        } catch (Exception ex) {
                            return ServerSentEvent.<String>builder()
                                    .comment("error: " + e.getMessage()).build();
                        }
                    }
                })
                .concatWith(Flux.just(ServerSentEvent.builder("data: [DONE]").build()));
    }

    // Unified endpoint that handles both streaming and non-streaming
    // Returns SSE when stream=true, JSON when stream=false (OpenAI API compatible)
    @PostMapping(value = "/completions")
    public Object completions(@RequestBody ChatRequest request) {
        log.info("Gateway request: model={} stream={}", request.getModel(), request.isStream());
        
        String userMessage = request.getMessages().stream()
                .filter(m -> "user".equals(m.getRole()))
                .map(ChatRequest.Message::getContent)
                .reduce((first, second) -> second)
                .orElse("");

        tokenGovernor.acquireTokenBudget(userMessage);

        if (request.isStream()) {
            // Return streaming response (SSE) - same as /completions/stream endpoint
            String requestId = "chatcmpl-" + UUID.randomUUID().toString();
            long created = System.currentTimeMillis() / 1000;

            return internalLlmClientService.streamCompletion(userMessage, request.getModel(), true)
                    .onErrorResume(error -> {
                        log.error("Error in streaming completion", error);
                        Map<String, Object> delta = new HashMap<>();
                        delta.put("content", "Error: " + error.getMessage());

                        Map<String, Object> choice = new HashMap<>();
                        choice.put("index", 0);
                        choice.put("delta", delta);
                        choice.put("finish_reason", "stop");

                        Map<String, Object> chunk = new HashMap<>();
                        chunk.put("id", requestId);
                        chunk.put("object", "chat.completion.chunk");
                        chunk.put("created", created);
                        chunk.put("model", request.getModel() != null ? request.getModel() : "gpt-4o");
                        chunk.put("choices", Collections.singletonList(choice));

                        try {
                            return Flux.just(objectMapper.writeValueAsString(chunk));
                        } catch (Exception e) {
                            log.error("Error creating error chunk", e);
                            return Flux.just("{\"error\":\"" + error.getMessage().replace("\"", "\\\"") + "\"}");
                        }
                    })
                    .map(content -> {
                        try {
                            // Content is already JSON from internal gateway (transformed chunks)
                            if (content != null && content.trim().startsWith("{")) {
                                return ServerSentEvent.builder(content).build();
                            }
                            
                            // Shouldn't reach here, but handle just in case
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
                            chunk.put("model", request.getModel() != null ? request.getModel() : "gpt-4o");
                            chunk.put("choices", Collections.singletonList(choice));

                            return ServerSentEvent
                                    .builder(objectMapper.writeValueAsString(chunk))
                                    .build();
                        } catch (Exception e) {
                            log.error("Error creating chunk", e);
                            Map<String, Object> errorChunk = new HashMap<>();
                            errorChunk.put("error", e.getMessage());
                            try {
                                return ServerSentEvent.builder(objectMapper.writeValueAsString(errorChunk)).build();
                            } catch (Exception ex) {
                                return ServerSentEvent.<String>builder()
                                        .comment("error: " + e.getMessage()).build();
                            }
                        }
                    })
                    .concatWith(Flux.just(ServerSentEvent.builder("data: [DONE]").build()));
        } else {
            // Return non-streaming response (single JSON)
            String content = null;
            try {
                // For non-streaming, call internal gateway with stream=false
                content = internalLlmClientService.streamCompletion(
                        userMessage,
                        request.getModel(),
                        false
                ).blockFirst();
            } catch (Exception e) {
                log.error("Error calling internal LLM gateway", e);
                content = "Error: " + e.getMessage();
            }

            // Ensure content is never null - Spring AI requires a valid message
            if (content == null || content.isEmpty()) {
                log.warn("Received null or empty content from internal gateway, using fallback");
                content = "Error: No response from internal LLM gateway";
            }

            // Always create a valid message object - Spring AI requires this
            Map<String, Object> message = new HashMap<>();
            message.put("role", "assistant");
            message.put("content", content != null ? content : "");

            // Always create a valid choice with message
            Map<String, Object> choice = new HashMap<>();
            choice.put("index", 0);
            choice.put("message", message);
            choice.put("finish_reason", "stop");

            // Always create a valid response structure
            Map<String, Object> result = new HashMap<>();
            result.put("id", "chatcmpl-" + UUID.randomUUID());
            result.put("object", "chat.completion");
            result.put("created", System.currentTimeMillis() / 1000);
            result.put("model", request.getModel() != null ? request.getModel() : "gpt-4o");
            result.put("choices", Collections.singletonList(choice));

            log.debug("Returning non-streaming response: choices={}, message={}", choice, message);
            return result;
        }
    }
}