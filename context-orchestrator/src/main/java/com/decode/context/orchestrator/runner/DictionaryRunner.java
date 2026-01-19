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

    @Override
    public void run(String... args) throws Exception {
        // Check if there are any PENDING symbols before processing
        long pendingCount = symbolRepository.countByAnalysisStatus("PENDING");
        
        if (pendingCount == 0) {
            log.info("No PENDING symbols found, skipping dictionary population on startup.");
            log.info("Dictionary population can be triggered manually via API if needed.");
            return;
        }

        log.info("Found {} PENDING symbols, starting dictionary population...", pendingCount);
        try {
            dictionaryService.populateDictionary();
        } catch (Exception e) {
            log.error("Dictionary Population Failed", e);
        }
    }
}
