package com.decode.context.orchestrator.config;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Manual ChatModel configuration.
 * Required because we're using spring-ai-openai (core) instead of 
 * spring-ai-openai-spring-boot-starter to avoid broken auto-configuration.
 */
@Configuration
public class OpenAiChatModelConfig {

    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    @Value("${spring.ai.openai.base-url}")
    private String baseUrl;

    @Value("${spring.ai.openai.chat.options.model:gpt-4o}")
    private String model;

    @Bean
    public ChatModel chatModel() {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .options(OpenAiChatOptions.builder()
                        .withModel(model)
                        .build())
                .build();
    }
}
