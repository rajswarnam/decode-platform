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
        log.info("Starting Dictionary Poupulation (via Gateway)...");
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

        GlobalDictionary entry = new GlobalDictionary();
        entry.setTechnicalName(techName);
        entry.setDomain(domain);
        entry.setBusinessName(parts[0].trim());
        entry.setStandardLabel(parts[1].trim());
        entry.setDescription(parts[2].trim());
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
