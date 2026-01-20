# DictionaryService Streaming Implementation Guide

## For Your Company Workspace

This guide provides the exact code changes needed to add streaming support to `DictionaryService` in your company workspace.

---

## Step 1: Update DictionaryService.java

**Add these methods to your `DictionaryService` class:**

```java
/**
 * Original method - kept for backward compatibility
 * Delegates to streaming-enabled version with null consumer
 */
public void populateDictionary() {
    populateDictionary(null);
}

    /**
     * Streaming-enabled method with progress updates
     * @param progressConsumer Optional consumer for progress updates (null-safe)
     * @param includeFailed If true, also retry symbols with FAILED status (default: true)
     */
    public void populateDictionary(java.util.function.Consumer<String> progressConsumer) {
        populateDictionary(progressConsumer, true);
    }

    /**
     * Streaming-enabled method with progress updates and retry option
     * @param progressConsumer Optional consumer for progress updates (null-safe)
     * @param includeFailed If true, also retry symbols with FAILED status
     */
    public void populateDictionary(java.util.function.Consumer<String> progressConsumer, boolean includeFailed) {
        log.info("Starting Dictionary Population (via Gateway)...{}", includeFailed ? " (including failed symbols)" : "");
        if (progressConsumer != null) {
            progressConsumer.accept("📚 Starting Dictionary Population..." + (includeFailed ? " (including failed symbols)" : ""));
        }
        
        ChatClient chatClient = chatClientBuilder.build();

        // Count total symbols to track progress (PENDING + optionally FAILED)
        long totalSymbols = symbolRepository.countByAnalysisStatus("PENDING");
        if (includeFailed) {
            totalSymbols += symbolRepository.countByAnalysisStatus("FAILED");
        }
        long processedCount = 0;

        while (true) {
            // Get PENDING symbols first
            List<Symbol> batch = symbolRepository.findTop50ByAnalysisStatus("PENDING");
            
            // If no PENDING and includeFailed=true, get FAILED symbols
            if (batch.isEmpty() && includeFailed) {
                batch = symbolRepository.findTop50ByAnalysisStatus("FAILED");
                if (!batch.isEmpty() && progressConsumer != null) {
                    progressConsumer.accept("🔄 Retrying failed symbols...");
                }
            }
        if (batch.isEmpty()) {
            log.info("All symbols processed/pending. Ingestion complete.");
            if (progressConsumer != null) {
                progressConsumer.accept("✅ Dictionary population complete. All symbols processed.");
            }
            break;
        }

        if (progressConsumer != null) {
            processedCount += batch.size();
            double progress = totalSymbols > 0 ? (processedCount * 100.0 / totalSymbols) : 0;
            progressConsumer.accept(String.format(
                "📖 Processing batch: %d symbols (Progress: %.1f%% - %d/%d)",
                batch.size(), progress, processedCount, totalSymbols
            ));
        }

        log.info("Processing batch of {} symbols via Gateway.", batch.size());

        int successCount = 0;
        int failCount = 0;

        for (Symbol symbol : batch) {
            try {
                boolean success = processSymbol(symbol, chatClient, progressConsumer);
                if (success) {
                    successCount++;
                } else {
                    failCount++;
                }
            } catch (Exception e) {
                log.error("Failed to process symbol {}: {}", symbol.getName(), e.getMessage());
                failCount++;
                if (progressConsumer != null) {
                    progressConsumer.accept("⚠️ Failed to process: " + symbol.getName());
                }
            }
        }

        if (progressConsumer != null && batch.size() > 0) {
            progressConsumer.accept(String.format(
                "✅ Batch complete: %d succeeded, %d failed",
                successCount, failCount
            ));
        }
    }
}

/**
 * Updated processSymbol to accept progressConsumer and return success status
 * @return true if successful, false otherwise
 */
private boolean processSymbol(Symbol symbol, ChatClient chatClient, 
                              java.util.function.Consumer<String> progressConsumer) {
    String techName = symbol.getName();
    String domain = symbol.getSourceFile().getProject().getDomain();
    if (domain == null)
        domain = "General";

    String promptText = String.format("""
            Act as a Business Analyst specializing in the '%s' domain.
            Analyze the technical symbol '%s' (Category: %s).
            Provide a structured output with:
            1. A Human-Readable Business Name (camelCase).
            2. A Standard Label (Title Case).
            3. A Business Description (1 sentence).

            Format: BusinessName|StandardLabel|Description
            Example for 'cust_id': customerIdentifier|Customer ID|Unique key identifying a customer.
            """, domain, techName, symbol.getCategory());

    try {
        // Use blocking call - returns clean response without SSE formatting
        // Note: We use .call().content() instead of .stream() because:
        // 1. DictionaryService needs full response to parse (can't parse incrementally)
        // 2. .stream() can include SSE formatting/metadata causing DB insert issues
        // 3. Progress streaming to client is handled separately via progressConsumer
        String response = chatClient.prompt(promptText).call().content();

        String[] parts = response.split("\\|");
        if (parts.length >= 3) {
            saveDictionaryEntry(techName, domain, parts);
            updateSymbolStatus(symbol, "COMPLETED");
            log.info("Mapped: {} -> {}", techName, parts[1].trim());
            if (progressConsumer != null) {
                progressConsumer.accept(String.format("✅ Mapped: %s → %s", techName, parts[1].trim()));
            }
            return true;
        } else {
            updateSymbolStatus(symbol, "FAILED");
            log.warn("Invalid format for {}", techName);
            if (progressConsumer != null) {
                progressConsumer.accept(String.format("⚠️ Invalid format for: %s", techName));
            }
            return false;
        }

    } catch (Exception e) {
        log.error("Failed to process {}: {}", techName, e.getMessage());
        updateSymbolStatus(symbol, "FAILED");
        if (progressConsumer != null) {
            progressConsumer.accept(String.format("❌ Error processing %s: %s", techName, e.getMessage()));
        }
        return false;
    }
}
```

**Key Changes:**
1. Original `populateDictionary()` now calls the new overloaded version
2. New `populateDictionary(Consumer<String>)` method accepts progress consumer
3. `processSymbol()` updated to accept consumer and return boolean
4. Progress updates sent via `progressConsumer.accept()` throughout

---

## Step 2: Add Count Method to SymbolRepository

**File**: `SymbolRepository.java`

```java
public interface SymbolRepository extends JpaRepository<Symbol, UUID> {
    List<Symbol> findTop50ByAnalysisStatus(String analysisStatus);
    
    // Add these methods for progress tracking
    long countByAnalysisStatus(String analysisStatus);
    
    // Optional: Query for both PENDING and FAILED in one call
    // List<Symbol> findTop50ByAnalysisStatusIn(List<String> statuses);
}
```

---

## Step 3: Create DictionaryController.java (NEW FILE)

**File**: `context-orchestrator/src/main/java/com/decode/context/orchestrator/controller/DictionaryController.java`

```java
package com.decode.context.orchestrator.controller;

import com.decode.context.orchestrator.service.DictionaryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/dictionary")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class DictionaryController {

    private final DictionaryService dictionaryService;

    /**
     * Streaming endpoint for dictionary population with real-time progress
     * Returns Server-Sent Events (SSE) for real-time updates
     */
    @PostMapping(value = "/populate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public StreamingResponseBody populateDictionaryStream() {
        log.info("Dictionary population triggered via streaming endpoint");

        return outputStream -> {
            java.io.PrintWriter writer = new java.io.PrintWriter(
                new java.io.OutputStreamWriter(outputStream, java.nio.charset.StandardCharsets.UTF_8)
            );

            try {
                // Call streaming-enabled method with progress consumer
                dictionaryService.populateDictionary(progress -> {
                    try {
                        writer.write("event: progress\n");
                        writer.write("data: " + progress + "\n\n");
                        writer.flush();
                    } catch (Exception e) {
                        log.error("Error sending progress event", e);
                    }
                });
            } catch (Exception e) {
                log.error("Error in dictionary population", e);
                try {
                    writer.write("event: error\n");
                    writer.write("data: " + e.getMessage() + "\n\n");
                    writer.flush();
                } catch (Exception flushError) {
                    log.error("Error sending error event", flushError);
                }
            } finally {
                try {
                    writer.write("event: complete\n");
                    writer.write("data: Dictionary population finished\n\n");
                    writer.flush();
                } catch (Exception e) {
                    log.debug("Error closing stream", e);
                }
                writer.close();
            }
        };
    }

    /**
     * Non-streaming endpoint (for backward compatibility or simple triggers)
     * Starts dictionary population in background thread
     */
    @PostMapping("/populate-sync")
    public java.util.Map<String, Object> populateDictionarySync() {
        log.info("Dictionary population triggered via sync endpoint");
        
        java.util.Map<String, Object> response = new java.util.HashMap<>();
        
        try {
            // Run in background thread
            Thread thread = new Thread(() -> {
                try {
                    dictionaryService.populateDictionary(); // Uses original method (no progress)
                } catch (Exception e) {
                    log.error("Dictionary population failed", e);
                }
            });
            thread.start();
            
            response.put("status", "STARTED");
            response.put("message", "Dictionary population started in background. Use /populate for streaming updates.");
            return response;
            
        } catch (Exception e) {
            log.error("Failed to start dictionary population", e);
            response.put("status", "ERROR");
            response.put("message", e.getMessage());
            return response;
        }
    }
}
```

---

## Step 4: Testing

### Test Streaming Endpoint

```bash
# Trigger streaming dictionary population
curl -X POST http://localhost:8082/api/dictionary/populate

# Expected output (SSE format):
# event: progress
# data: 📚 Starting Dictionary Population...
#
# event: progress
# data: 📖 Processing batch: 50 symbols (Progress: 5.0% - 50/1000)
#
# event: progress
# data: ✅ Mapped: customerId → Customer ID
#
# event: complete
# data: Dictionary population finished
```

### Test Sync Endpoint

```bash
# Trigger background dictionary population
curl -X POST http://localhost:8082/api/dictionary/populate-sync

# Expected response:
# {"status":"STARTED","message":"Dictionary population started in background..."}
```

---

## Handling FAILED Status

**Important**: The current query only processes `"PENDING"` symbols. To also retry `"FAILED"` symbols, the implementation includes an `includeFailed` parameter.

### Option 1: Process PENDING and FAILED Together (Recommended)

The implementation above processes PENDING first, then FAILED (if `includeFailed=true`).

### Option 2: Query Both Statuses in Single Query

If you want to mix PENDING and FAILED in the same batch:

```java
// Add to SymbolRepository
@Query("SELECT s FROM Symbol s WHERE s.analysisStatus IN :statuses ORDER BY s.id LIMIT 50")
List<Symbol> findTop50ByAnalysisStatusIn(@Param("statuses") List<String> statuses);

// Then in populateDictionary:
List<String> statuses = includeFailed ? 
    List.of("PENDING", "FAILED") : List.of("PENDING");
List<Symbol> batch = symbolRepository.findTop50ByAnalysisStatusIn(statuses);
```

### Option 3: Reset FAILED to PENDING First

```java
// At start of populateDictionary, reset failed symbols
if (includeFailed) {
    int resetCount = symbolRepository.resetFailedToPending();
    if (progressConsumer != null && resetCount > 0) {
        progressConsumer.accept("🔄 Reset " + resetCount + " failed symbols to PENDING");
    }
}
```

**Note**: Option 3 requires a custom repository method for batch update.

---

## Summary of Changes

1. ✅ **DictionaryService**: Add overloaded `populateDictionary(Consumer<String>, boolean)` method
2. ✅ **DictionaryService**: Query both PENDING and FAILED symbols (with option)
3. ✅ **DictionaryService**: Update `processSymbol()` to accept consumer and return boolean
4. ✅ **SymbolRepository**: Add `countByAnalysisStatus()` method
5. ✅ **DictionaryController**: New REST controller with streaming endpoint

**Backward Compatibility**: ✅ Original `populateDictionary()` still works (calls new method with null consumer and `includeFailed=true`)

---

## Frontend Integration Example

```typescript
// Connect to streaming endpoint
const eventSource = new EventSource('http://localhost:8082/api/dictionary/populate', {
  method: 'POST'
});

eventSource.addEventListener('progress', (event) => {
  console.log('Progress:', event.data);
  // Update UI: show progress message
});

eventSource.addEventListener('complete', (event) => {
  console.log('Complete:', event.data);
  eventSource.close();
});

eventSource.addEventListener('error', (event) => {
  console.error('Error:', event.data);
  eventSource.close();
});
```

---

## Notes for Your Company Workspace

- Follows the same pattern as `SemanticExplorerController.query()` and `AgentOrchestrator.executeSwarm()`
- Uses `StreamingResponseBody` for SSE streaming
- Progress updates sent via `Consumer<String>` pattern
- Backward compatible - existing code continues to work
