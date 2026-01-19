package com.decode.gateway.controller;

import com.decode.gateway.dto.ChatRequest;
import com.decode.gateway.service.TokenGovernor;
import com.decode.gateway.service.InternalLlmClientService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
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

    // Dedicated streaming endpoint (alternative to /completions with stream=true)
    @PostMapping(value = "/completions/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter completionsStream(@RequestBody ChatRequest request) {
        log.debug("Gateway streaming request: model={} stream={}", request.getModel(), request.isStream());

        String userMessage = request.getMessages().stream()
                .filter(m -> "user".equals(m.getRole()))
                .map(ChatRequest.Message::getContent)
                .reduce((first, second) -> second)
                .orElse("");

        tokenGovernor.acquireTokenBudget(userMessage);

        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        String requestId = "chatcmpl-" + UUID.randomUUID().toString();
        long created = System.currentTimeMillis() / 1000;

        // Process streaming chunks asynchronously
        internalLlmClientService.streamCompletion(userMessage, request.getModel(), true)
                .doOnNext(chunk -> {
                    try {
                        // Chunk is already JSON string from internal gateway
                        // SseEmitter.event().data() formats it as SSE automatically
                        emitter.send(SseEmitter.event()
                                .data(chunk));
                    } catch (Exception e) {
                        log.error("Error sending SSE chunk", e);
                        emitter.completeWithError(e);
                    }
                })
                .doOnComplete(() -> {
                    try {
                        emitter.send(SseEmitter.event()
                                .data("[DONE]"));
                        emitter.complete();
                    } catch (Exception e) {
                        log.error("Error completing SSE", e);
                        emitter.completeWithError(e);
                    }
                })
                .doOnError(error -> {
                    log.error("Error in streaming completion", error);
                    try {
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

                        emitter.send(SseEmitter.event()
                                .data(objectMapper.writeValueAsString(chunk)));
                        emitter.send(SseEmitter.event()
                                .data("[DONE]"));
                        emitter.complete();
                    } catch (Exception e) {
                        log.error("Error sending error chunk", e);
                        emitter.completeWithError(e);
                    }
                })
                .subscribe();

        // Return SseEmitter directly - Spring MVC handles it specially
        return emitter;
    }

    // Unified endpoint that handles both streaming and non-streaming
    // Returns Flux<ServerSentEvent> when stream=true, Map when stream=false (OpenAI API compatible)
    @PostMapping(value = "/completions")
    public Object completions(@RequestBody ChatRequest request) {
        log.debug("Gateway request: model={} stream={}", request.getModel(), request.isStream());
        
        String userMessage = request.getMessages().stream()
                .filter(m -> "user".equals(m.getRole()))
                .map(ChatRequest.Message::getContent)
                .reduce((first, second) -> second)
                .orElse("");

        tokenGovernor.acquireTokenBudget(userMessage);

        if (request.isStream()) {
            // Use SseEmitter for Spring MVC (not WebFlux)
            SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
            String requestId = "chatcmpl-" + UUID.randomUUID().toString();
            long created = System.currentTimeMillis() / 1000;

            // Process streaming chunks asynchronously
            internalLlmClientService.streamCompletion(userMessage, request.getModel(), true)
                    .doOnNext(chunk -> {
                        try {
                            // Chunk is already JSON string from internal gateway
                            // SseEmitter.event().data() formats it as SSE automatically
                            emitter.send(SseEmitter.event()
                                    .data(chunk));
                        } catch (Exception e) {
                            log.error("Error sending SSE chunk", e);
                            emitter.completeWithError(e);
                        }
                    })
                    .doOnComplete(() -> {
                        try {
                            emitter.send(SseEmitter.event()
                                    .data("[DONE]"));
                            emitter.complete();
                        } catch (Exception e) {
                            log.error("Error completing SSE", e);
                            emitter.completeWithError(e);
                        }
                    })
                    .doOnError(error -> {
                        log.error("Error in streaming completion", error);
                        try {
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

                            emitter.send(SseEmitter.event()
                                    .data(objectMapper.writeValueAsString(chunk)));
                            emitter.send(SseEmitter.event()
                                    .data("[DONE]"));
                            emitter.complete();
                        } catch (Exception e) {
                            log.error("Error sending error chunk", e);
                            emitter.completeWithError(e);
                        }
                    })
                    .subscribe(); // Start the reactive stream

            // Return SseEmitter directly - Spring MVC handles it specially
            return emitter;
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
            return result; // Return Map directly - Spring auto-sets Content-Type to application/json
        }
    }
}