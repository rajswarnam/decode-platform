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
        new Thread(() -> {
            log.info("--- Starting Background Auto-Discovery ---");

            String workspaceRoot = System.getenv("SCAN_ROOT");
            if (workspaceRoot == null || workspaceRoot.isEmpty()) {
                workspaceRoot = "/workspace";
            }

            log.info("Scanning Workspace Root: {}", workspaceRoot);

            try {
                projectDiscoveryService.discoverAndRegisterProjects(workspaceRoot, "local-git-placeholder",
                        "Automated Scan");
                log.info("--- Background Auto-Discovery Complete ---");
            } catch (Exception e) {
                log.error("Error during background auto-discovery", e);
            }
        }).start();
    }
}
