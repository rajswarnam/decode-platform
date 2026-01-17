package com.decode.context.orchestrator.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/debug")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class DebugController {

    private final VectorStore vectorStore;

    @GetMapping("/test-vector-search")
    public String testVectorSearch(@RequestParam(defaultValue = "order procurement purchase") String query) {
        log.info("Testing vector search with query: {}", query);
        
        try {
            SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(10)
                .build();
            
            List<Document> docs = vectorStore.similaritySearch(request);
            
            log.info("Vector search returned {} documents", docs.size());
            
            StringBuilder result = new StringBuilder();
            result.append("Query: ").append(query).append("\n");
            result.append("Results: ").append(docs.size()).append(" documents\n\n");
            
            for (int i = 0; i < Math.min(5, docs.size()); i++) {
                Document doc = docs.get(i);
                result.append("Document ").append(i + 1).append(":\n");
                result.append("  Content: ").append(doc.getText().substring(0, Math.min(200, doc.getText().length()))).append("...\n");
                result.append("  Metadata: ").append(doc.getMetadata()).append("\n\n");
            }
            
            return result.toString();
            
        } catch (Exception e) {
            log.error("Vector search failed", e);
            return "ERROR: " + e.getMessage() + "\n" + e.getClass().getName();
        }
    }
}
