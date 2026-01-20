package com.decode.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(excludeName = {
    // Exclude all Spring AI Azure OpenAI auto-configurations
    // We use InternalLlmClientService with RestTemplate instead
    "org.springframework.ai.autoconfigure.azure.openai.AzureOpenAiAutoConfiguration",
    "org.springframework.ai.autoconfigure.azure.openai.AzureOpenAiChatAutoConfiguration",
    // Exclude the client builder configuration (exact class name from error)
    "org.springframework.ai.model.azure.openai.autoconfigure.AzureOpenAIClientBuilderConfiguration"
})
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
