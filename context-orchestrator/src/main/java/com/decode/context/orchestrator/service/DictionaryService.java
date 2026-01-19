package com.decode.context.orchestrator.service;

import com.decode.context.orchestrator.domain.GlobalDictionary;
import com.decode.context.orchestrator.domain.Symbol;
import com.decode.context.orchestrator.repository.GlobalDictionaryRepository;
import com.decode.context.orchestrator.repository.SymbolRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class DictionaryService {
    

    private final GlobalDictionaryRepository dictionaryRepository;
    private final SymbolRepository symbolRepository;
    private final ChatClient.Builder chatClientBuilder;

    public void populateDictionary() {
        log.info("Starting Dictionary Population (via Gateway)...");
        ChatClient chatClient = chatClientBuilder.build();

        while (true) {
            List<Symbol> batch = symbolRepository.findTop50ByAnalysisStatus("PENDING");
            if (batch.isEmpty()) {
                log.info("All symbols processed/pending. Ingestion complete.");
                break;
            }

            log.info("Processing batch of {} symbols via Gateway.", batch.size());

            for (Symbol symbol : batch) {
                processSymbol(symbol, chatClient);
            }
        }
    }
    // ...existing code...
    /**
     * Streaming-enabled method with progress updates
     * @param progressConsumer Optional consumer for progress updates (null-safe)
     */
    public void populateDictionary(java.util.function.Consumer<String> progressConsumer, boolean includeFailed) {
        log.info("Starting Dictionary Population (via Gateway)...");
        if (progressConsumer != null) {
            progressConsumer.accept("📚 Starting Dictionary Population...");
        }
        ChatClient chatClient = chatClientBuilder.build();

        // Count total symbols to track progress
        long totalSymbols = symbolRepository.countByAnalysisStatus("PENDING");
        long processedCount = 0;

        while (true) {
            List<Symbol> batch = symbolRepository.findTop50ByAnalysisStatus("PENDING");
            if (batch.isEmpty() && includeFailed) {
                batch = symbolRepository.findTop50ByAnalysisStatus("FAILED");
                log.info("All symbols processed/pending. Ingestion complete.");
                if (batch.isEmpty() && progressConsumer != null) {
                    progressConsumer.accept("✅ Dictionary population complete. All symbols processed.");
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
    // ...existing code...
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
            // Direct call. Gateway handles TPM.
            // Use non-streaming LLM call (single response)
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
    private void processSymbol(Symbol symbol, ChatClient chatClient) {
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
            // Direct call. Gateway handles TPM.
            String response = chatClient.prompt(promptText).call().content();

            String[] parts = response.split("\\|");
            if (parts.length >= 3) {
                saveDictionaryEntry(techName, domain, parts);
                updateSymbolStatus(symbol, "COMPLETED");
                log.info("Mapped: {} -> {}", techName, parts[1].trim());
            } else {
                updateSymbolStatus(symbol, "FAILED");
                log.warn("Invalid format for {}", techName);
            }

        } catch (Exception e) {
            log.error("Failed to process {}: {}", techName, e.getMessage());
            updateSymbolStatus(symbol, "FAILED");
        }
    }

    @Transactional
    public void saveDictionaryEntry(String techName, String domain, String[] parts) {
        if (dictionaryRepository.existsByTechnicalNameAndDomain(techName, domain))
            return;

        String businessName = parts[0].trim();
        String standardLabel = parts[1].trim();
        String description = parts[2].trim();
        String domainVal = domain;
        String techNameVal = techName;

        if (businessName.length() > 255) {
            log.error("businessName > 255: {} ({} chars)", businessName, businessName.length());
        }
        if (standardLabel.length() > 255) {
            log.error("standardLabel > 255: {} ({} chars)", standardLabel, standardLabel.length());
        }
        if (description.length() > 255) {
            log.error("description > 255: {} ({} chars)", description, description.length());
        }
        if (domainVal.length() > 255) {
            log.error("domain > 255: {} ({} chars)", domainVal, domainVal.length());
        }
        if (techNameVal.length() > 255) {
            log.error("technicalName > 255: {} ({} chars)", techNameVal, techNameVal.length());
        }

        GlobalDictionary entry = new GlobalDictionary();
        entry.setTechnicalName(techNameVal);
        entry.setDomain(domainVal);
        entry.setBusinessName(businessName);
        entry.setStandardLabel(standardLabel);
        entry.setDescription(description);
        entry.setConfidenceScore(0.95);
        entry.setCreatedByAgent(true);
        dictionaryRepository.save(entry);
    }

    @Transactional
    public void updateSymbolStatus(Symbol symbol, String status) {
        symbol.setAnalysisStatus(status);
        symbolRepository.save(symbol);
    }
}
