package com.decode.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(excludeName = {
    // Explicitly exclude Spring AI Azure OpenAI auto-configuration classes
    // We use InternalLlmClientService (RestTemplate) -> Internal LLM Gateway instead
    "org.springframework.ai.autoconfigure.azure.openai.AzureOpenAiAutoConfiguration",
    "org.springframework.ai.autoconfigure.azure.openai.AzureOpenAiChatAutoConfiguration",
    "org.springframework.ai.model.azure.openai.autoconfigure.AzureOpenAIClientBuilderConfiguration"
})
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
