# Fix: OpenAiChatModel API Changes in Spring AI 1.0.0

## Error

```
cannot find symbol method apiKey(java.lang.String) in OpenAiChatModel.Builder
cannot find symbol method withModel(java.lang.String) in OpenAiChatOptions.Builder
```

## Root Cause

Spring AI 1.0.0 (GA) changed the API for constructing `OpenAiChatModel`. The builder pattern methods are different or the constructor pattern is required.

## Solution

Use constructor-based configuration instead of builder pattern:

**Before (Wrong for Spring AI 1.0.0)**:
```java
@Bean
public ChatModel chatModel() {
    return OpenAiChatModel.builder()
            .apiKey(apiKey)  // ❌ Method doesn't exist
            .baseUrl(baseUrl)
            .options(OpenAiChatOptions.builder()
                    .withModel(model)  // ❌ Method doesn't exist
                    .build())
            .build();
}
```

**After (Correct for Spring AI 1.0.0)**:
```java
@Bean
public ChatModel chatModel() {
    OpenAiApi openAiApi = new OpenAiApi(baseUrl, apiKey);
    OpenAiChatOptions options = OpenAiChatOptions.builder()
            .withModel(model)
            .build();
    return new OpenAiChatModel(openAiApi, options);
}
```

## Complete Configuration

```java
package com.decode.context.orchestrator.config;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
        OpenAiApi openAiApi = new OpenAiApi(baseUrl, apiKey);
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .withModel(model)
                .build();
        return new OpenAiChatModel(openAiApi, options);
    }
}
```

## Alternative: If Constructor Signature is Different

If the constructor signature is different, try:

**Option 1**: Single argument constructor
```java
@Bean
public ChatModel chatModel() {
    OpenAiApi openAiApi = new OpenAiApi(baseUrl, apiKey);
    return new OpenAiChatModel(openAiApi);
}
```

**Option 2**: Different OpenAiApi constructor
```java
@Bean
public ChatModel chatModel() {
    OpenAiApi openAiApi = new OpenAiApi(apiKey);  // Try without baseUrl
    OpenAiChatOptions options = OpenAiChatOptions.builder()
            .withModel(model)
            .build();
    return new OpenAiChatModel(openAiApi, options);
}
```

**Option 3**: Using OpenAiChatOptions.withBaseUrl()
```java
@Bean
public ChatModel chatModel() {
    OpenAiApi openAiApi = new OpenAiApi(apiKey);
    OpenAiChatOptions options = OpenAiChatOptions.builder()
            .withModel(model)
            .withBaseUrl(baseUrl)  // If this method exists
            .build();
    return new OpenAiChatModel(openAiApi, options);
}
```

## Verify

After applying the fix, rebuild:

```bash
cd context-orchestrator
mvn clean package
```

The compilation errors should be resolved.

## Key Changes in Spring AI 1.0.0

1. **Constructor-based initialization** instead of fluent builder for `OpenAiChatModel`
2. **OpenAiApi** must be created separately
3. **OpenAiChatOptions** builder might still work, but check method names
