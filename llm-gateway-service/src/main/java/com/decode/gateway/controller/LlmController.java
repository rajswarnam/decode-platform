package com.decode.gateway.controller;

import com.decode.gateway.dto.ChatRequest;
import com.decode.gateway.service.TokenGovernor;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
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

        private final ChatClient.Builder chatClientBuilder;
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
                        String requestId = "chatcmpl-" + UUID.randomUUID().toString();
                        long created = System.currentTimeMillis() / 1000;

                        return chatClientBuilder.build()
                                        .prompt(userMessage)
                                        .stream()
                                        .chatResponse()
                                        .map(response -> {
                                                try {
                                                        String content = "";
                                                        if (response.getResult() != null
                                                                        && response.getResult().getOutput() != null) {
                                                                content = response.getResult().getOutput().getText();
                                                                if (content == null)
                                                                        content = "";
                                                        }

                                                        String finishReason = null;
                                                        if (response.getResult() != null
                                                                        && response.getResult().getMetadata() != null) {
                                                                finishReason = response.getResult().getMetadata()
                                                                                .getFinishReason();
                                                                // Normalize "null" string to actual null
                                                                if ("null".equals(finishReason))
                                                                        finishReason = null;
                                                        }

                                                        Map<String, Object> delta = new HashMap<>();
                                                        delta.put("content", content);

                                                        Map<String, Object> choice = new HashMap<>();
                                                        choice.put("index", 0);
                                                        choice.put("delta", delta);
                                                        choice.put("finish_reason", finishReason);

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
                        var response = chatClientBuilder.build().prompt(userMessage).call().chatResponse();
                        try {
                                Map<String, Object> message = new HashMap<>();
                                message.put("role", "assistant");
                                message.put("content", response.getResult().getOutput().getText());

                                Map<String, Object> choice = new HashMap<>();
                                choice.put("index", 0);
                                choice.put("message", message);
                                choice.put("finish_reason", "stop");

                                Map<String, Object> result = new HashMap<>();
                                result.put("id", response.getMetadata().getId() != null ? response.getMetadata().getId()
                                                : "chatcmpl-" + UUID.randomUUID());
                                result.put("object", "chat.completion");
                                result.put("created", System.currentTimeMillis() / 1000);
                                result.put("model", request.getModel());
                                result.put("choices", Collections.singletonList(choice));

                                return Flux.just(ServerSentEvent.builder(objectMapper.writeValueAsString(result))
                                                .build());
                        } catch (Exception e) {
                                return Flux.just(ServerSentEvent.<String>builder()
                                                .data("{\"error\": \"" + e.getMessage() + "\"}").build());
                        }
                }
        }
}
