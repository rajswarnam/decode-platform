package com.decode.context.orchestrator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(excludeName = {
    "org.springframework.ai.autoconfigure.chat.client.observation.ChatClientObservationAutoConfiguration",
    "org.springframework.ai.autoconfigure.vectorstore.qdrant.QdrantVectorStoreAutoConfiguration"
})
public class ContextOrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(ContextOrchestratorApplication.class, args);
    }

}
