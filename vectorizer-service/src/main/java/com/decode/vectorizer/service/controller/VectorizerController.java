package com.decode.vectorizer.service.controller;

import com.decode.vectorizer.service.repository.SymbolRepository;
import com.decode.vectorizer.service.service.VectorizerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/vectorizer")
@RequiredArgsConstructor
@Slf4j
public class VectorizerController {

    private final VectorizerService vectorizerService;
    private final SymbolRepository symbolRepository;

    @PostMapping("/trigger")
    public ResponseEntity<String> triggerVectorization(@RequestParam UUID projectId) {
        log.info("Received trigger for vectorizing project ID: {}", projectId);
        new Thread(() -> {
            try {
                vectorizerService.vectorizeProject(projectId);
            } catch (Exception e) {
                log.error("Vectorization failed", e);
            }
        }).start();
        return ResponseEntity.ok("Vectorization triggered for " + projectId);
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getVectorizationStatus(@RequestParam UUID projectId) {
        Map<String, Object> status = new HashMap<>();
        
        long symbolCount = symbolRepository.findAllBySourceFile_Project_Id(projectId).size();
        
        status.put("projectId", projectId.toString());
        status.put("embeddingCount", symbolCount);
        status.put("status", symbolCount > 0 ? "COMPLETED" : "PENDING");
        
        return ResponseEntity.ok(status);
    }
}
