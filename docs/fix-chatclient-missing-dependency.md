# Fix: ChatClient Package Not Found

## Error

```
package org.springframework.ai.chat.client does not exist
cannot find symbol class ChatClient
```

## Root Cause

`spring-ai-openai` (core dependency) provides `ChatModel` and `OpenAiChatModel`, but **does not include `ChatClient`**. 

`ChatClient` is in a separate module: `spring-ai-core`.

## Solution

Add `spring-ai-core` dependency to `pom.xml`:

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-core</artifactId>
    <version>${spring-ai.version}</version>
</dependency>
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-openai</artifactId>
    <version>${spring-ai.version}</version>
</dependency>
```

## Complete Dependency Structure

For Spring AI 1.0.0, you need:

1. **`spring-ai-core`** - Provides `ChatClient`, `ChatModel` interface, and core abstractions
2. **`spring-ai-openai`** - Provides `OpenAiChatModel` implementation
3. **Manual Configuration** - `OpenAiChatModelConfig` and `ChatClientConfig`

## Why This Works

- `spring-ai-core` contains `ChatClient` and `ChatClient.Builder`
- `spring-ai-openai` contains `OpenAiChatModel` implementation
- Together they provide all the classes needed for chat functionality
- No auto-configuration means no broken observation classes

## Verify

After adding the dependency, rebuild:

```bash
cd context-orchestrator
mvn clean package
```

The compilation errors should be resolved.
