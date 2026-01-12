package com.decode.context.orchestrator.config;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class HttpTimeoutConfig implements WebMvcConfigurer {

    @Bean
    public RestClientCustomizer restClientCustomizer() {
        return restClientBuilder -> restClientBuilder.requestFactory(new SimpleClientHttpRequestFactory() {
            {
                setConnectTimeout(300_000); // 5 Minutes
                setReadTimeout(300_000); // 5 Minutes
            }
        });
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setDefaultTimeout(300_000); // 5 Minutes for SSE/Async
    }
}
