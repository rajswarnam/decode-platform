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

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/api/vectorizer")
@RequiredArgsConstructor
@Slf4j
public class VectorizerController {

    private final VectorizerService vectorizerService;
    private final SymbolRepository symbolRepository;
    
    // Thread pool to limit concurrent vectorization (max 5 concurrent to avoid overwhelming Qdrant)
    private ExecutorService vectorizationExecutor;
    private final AtomicInteger activeVectorizations = new AtomicInteger(0);
    private final AtomicInteger queuedVectorizations = new AtomicInteger(0);
    
    @PostConstruct
    public void init() {
        // Limit to 5 concurrent vectorizations to prevent overwhelming Qdrant and system resources
        int maxConcurrent = 5;
        vectorizationExecutor = Executors.newFixedThreadPool(maxConcurrent);
        log.info("Vectorizer thread pool initialized with {} concurrent threads", maxConcurrent);
    }
    
    @PreDestroy
    public void shutdown() {
        if (vectorizationExecutor != null) {
            log.info("Shutting down vectorizer thread pool...");
            vectorizationExecutor.shutdown();
        }
    }

    @PostMapping("/trigger")
    public ResponseEntity<String> triggerVectorization(@RequestParam UUID projectId) {
        log.info("Received trigger for vectorizing project ID: {}", projectId);
        
        int queued = queuedVectorizations.incrementAndGet();
        int active = activeVectorizations.get();
        
        log.info("📊 Vectorization queue: {} active, {} queued (including this request)", active, queued);
        
        vectorizationExecutor.submit(() -> {
            int currentActive = activeVectorizations.incrementAndGet();
            queuedVectorizations.decrementAndGet();
            
            log.info("🔄 Starting vectorization for project {} ({} active vectorizations)", projectId, currentActive);
            
            try {
                vectorizerService.vectorizeProject(projectId);
                log.info("✅ Completed vectorization for project {}", projectId);
            } catch (Exception e) {
                log.error("❌ Vectorization failed for project {}: {}", projectId, e.getMessage(), e);
            } finally {
                int remaining = activeVectorizations.decrementAndGet();
                log.info("🏁 Finished vectorization for project {} ({} active vectorizations remaining)", projectId, remaining);
            }
        });
        
        return ResponseEntity.ok("Vectorization queued for " + projectId + " (queue position: " + queued + ")");
    }
    
    @GetMapping("/queue-status")
    public ResponseEntity<Map<String, Object>> getQueueStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("active", activeVectorizations.get());
        status.put("queued", queuedVectorizations.get());
        return ResponseEntity.ok(status);
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
