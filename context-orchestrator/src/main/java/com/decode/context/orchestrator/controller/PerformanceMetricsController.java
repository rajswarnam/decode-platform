package com.decode.context.orchestrator.controller;

import com.decode.context.orchestrator.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/metrics")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@Slf4j
public class PerformanceMetricsController {

    private static final long POSTGRES_TARGET_MS = 200;
    private static final long VECTOR_TARGET_MS = 50;

    private final ProjectRepository projectRepository;
    private final VectorStore vectorStore;

    @GetMapping("/performance")
    public ResponseEntity<Map<String, Object>> getPerformanceMetrics() {
        Map<String, Object> response = new HashMap<>();

        long postgresMs = measurePostgresLatency();
        long vectorMs = measureVectorLatency();

        response.put("postgresMs", postgresMs);
        response.put("vectorMs", vectorMs);
        response.put("postgresTargetMs", POSTGRES_TARGET_MS);
        response.put("vectorTargetMs", VECTOR_TARGET_MS);
        response.put("postgresWithinTarget", postgresMs <= POSTGRES_TARGET_MS);
        response.put("vectorWithinTarget", vectorMs <= VECTOR_TARGET_MS);

        return ResponseEntity.ok(response);
    }

    private long measurePostgresLatency() {
        long start = System.nanoTime();
        projectRepository.count();
        return (System.nanoTime() - start) / 1_000_000;
    }

    private long measureVectorLatency() {
        try {
            SearchRequest request = SearchRequest.builder()
                    .query("order")
                    .topK(5)
                    .build();
            long start = System.nanoTime();
            List<Document> docs = vectorStore.similaritySearch(request);
            long duration = (System.nanoTime() - start) / 1_000_000;
            log.debug("Vector search returned {} docs", docs.size());
            return duration;
        } catch (Exception e) {
            log.warn("Vector search failed for performance check: {}", e.getMessage());
            return -1;
        }
    }
}
