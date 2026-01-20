package com.decode.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(exclude = {
    // Exclude all Spring AI Azure OpenAI auto-configurations
    // We use InternalLlmClientService with RestTemplate instead
    org.springframework.ai.autoconfigure.azure.openai.AzureOpenAiAutoConfiguration.class,
    org.springframework.ai.autoconfigure.azure.openai.AzureOpenAiChatAutoConfiguration.class
    // Note: If the above don't work, try excluding the specific builder config
    // org.springframework.ai.model.azure.openai.autoconfigure.AzureOpenAIClientBuilderConfiguration.class
})
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
