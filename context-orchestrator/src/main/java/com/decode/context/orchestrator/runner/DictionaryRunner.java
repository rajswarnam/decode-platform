package com.decode.context.orchestrator.runner;

import com.decode.context.orchestrator.service.DictionaryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class DictionaryRunner implements CommandLineRunner {

    private final DictionaryService dictionaryService;
    private final com.decode.context.orchestrator.repository.SymbolRepository symbolRepository;
    
    // Make this configurable - disable by default to avoid running on every restart
    @org.springframework.beans.factory.annotation.Value("${dictionary.auto-populate-on-startup:false}")
    private boolean autoPopulateOnStartup;

    @Override
    public void run(String... args) throws Exception {
        // Skip if auto-populate is disabled
        if (!autoPopulateOnStartup) {
            log.info("Dictionary auto-population on startup is disabled.");
            log.info("Dictionary population can be triggered manually via API or during ingestion.");
            return;
        }
        
        // Check if there are any PENDING symbols before processing
        long pendingCount = symbolRepository.countByAnalysisStatus("PENDING");
        
        if (pendingCount == 0) {
            log.info("No PENDING symbols found, skipping dictionary population on startup.");
            log.info("Dictionary population can be triggered manually via API if needed.");
            return;
        }

        log.info("Found {} PENDING symbols, starting dictionary population...", pendingCount);
        log.info("Note: Dictionary population should ideally run during ingestion, not on startup.");
        log.info("Consider disabling auto-populate-on-startup and triggering it during ingestion instead.");
        
        try {
            dictionaryService.populateDictionary();
        } catch (Exception e) {
            log.error("Dictionary Population Failed", e);
        }
    }
}
