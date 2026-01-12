package com.decode.api.gateway.config;

import org.springframework.ai.mcp.server.transport.ServerMcpTransport;
import org.springframework.ai.mcp.server.transport.StdioServerTransport;
import org.springframework.ai.mcp.server.transport.WebFluxSseServerTransport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

@Configuration
public class McpConfig {

    @Bean
    @ConditionalOnProperty(name = "USE_STDIO", havingValue = "true")
    public ServerMcpTransport stdioServerTransport(ObjectMapper mapper) {
        return new StdioServerTransport(mapper);
    }

    @Bean
    @ConditionalOnProperty(name = "USE_STDIO", havingValue = "false", matchIfMissing = true)
    public ServerMcpTransport sseServerTransport(ObjectMapper mapper) {
        return new WebFluxSseServerTransport(mapper, "/mcp/message");
    }

    @Bean
    public ToolCallbackProvider toolCallbackProvider() {
        return new MethodToolCallbackProvider();
    }
}
