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
    }

    // Non-streaming endpoint
    @PostMapping(value = "/completions", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> completions(@RequestBody ChatRequest request) {
        log.info("Gateway non-streaming request: model={} stream={}", request.getModel(), request.isStream());

        String userMessage = request.getMessages().stream()
                .filter(m -> "user".equals(m.getRole()))
                .map(ChatRequest.Message::getContent)
                .reduce((first, second) -> second)
                .orElse("");

        tokenGovernor.acquireTokenBudget(userMessage);

        String content = null;
        try {
            content = internalLlmClientService.streamCompletion(
                    userMessage,
                    request.getModel(),
                    false
            ).blockFirst(); // Get single result (non-streaming)
        } catch (Exception e) {
            log.error("Error calling internal LLM gateway", e);
            content = "Error: " + e.getMessage();
        }

        // Ensure content is never null - Spring AI requires a valid message
        if (content == null || content.isEmpty()) {
            log.warn("Received null or empty content from internal gateway, using fallback");
            content = "Error: No response from internal LLM gateway";
        }

        Map<String, Object> message = new HashMap<>();
        message.put("role", "assistant");
        message.put("content", content);

        Map<String, Object> choice = new HashMap<>();
        choice.put("index", 0);
        choice.put("message", message); // Always ensure message is not null
        choice.put("finish_reason", "stop");

        Map<String, Object> result = new HashMap<>();
        result.put("id", "chatcmpl-" + UUID.randomUUID());
        result.put("object", "chat.completion");
        result.put("created", System.currentTimeMillis() / 1000);
        result.put("model", request.getModel() != null ? request.getModel() : "gpt-4o");
        result.put("choices", Collections.singletonList(choice)); // Always include at least one choice

        return result;
    }
}