package com.decode.ingestion.engine;

import com.decode.ingestion.engine.service.ProjectDiscoveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class IngestionRunner implements CommandLineRunner {

    private final ProjectDiscoveryService projectDiscoveryService;

    @Override
    public void run(String... args) throws Exception {
        // Run cleanup on startup to remove duplicates from previous bug
        projectDiscoveryService.cleanupDuplicates();
        
        // Disable auto-scan to prevent re-creating duplicates
        // Note: Git clone logic will handle project registration correctly via API
        log.info("Startup cleanup complete. Ready for API ingestion.");
    }
}
