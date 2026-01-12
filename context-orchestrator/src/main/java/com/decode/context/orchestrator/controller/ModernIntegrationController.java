package com.decode.context.orchestrator.controller;

import com.decode.context.orchestrator.service.ContractMappingService;
import com.decode.context.orchestrator.service.NoSqlContextualizer;
import com.decode.context.orchestrator.service.StitchingEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;

@RestController
@RequestMapping("/api/v1/modern")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ModernIntegrationController {

    private final ContractMappingService contractMappingService;
    private final StitchingEngine stitchingEngine;
    private final NoSqlContextualizer noSqlContextualizer;

    @PostMapping("/ingest-contract")
    public ResponseEntity<String> ingestContract(@RequestParam String filePath, @RequestParam String domain) {
        contractMappingService.parseVendorYaml(new File(filePath), domain);
        return ResponseEntity.ok("Vendor YAML contract ingested successfully.");
    }

    @PostMapping("/stitch")
    public ResponseEntity<String> stitchTransaction(
            @RequestParam String projectName,
            @RequestParam String correlationId,
            @RequestParam String egressRef,
            @RequestParam String ingressRef) {
        stitchingEngine.stitchBlackBoxTransaction(projectName, correlationId, egressRef, ingressRef);
        return ResponseEntity.ok("Transaction stitched successfully. Synthetic lineage created.");
    }

    @PostMapping("/infer-nosql")
    public ResponseEntity<String> inferNoSql(@RequestParam String projectName) {
        noSqlContextualizer.inferNoSqlBusinessRules(projectName);
        return ResponseEntity.ok("NoSQL behavioral inference complete.");
    }
}
