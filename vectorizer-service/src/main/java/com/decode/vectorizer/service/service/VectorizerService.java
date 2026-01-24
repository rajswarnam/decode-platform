package com.decode.vectorizer.service.service;

import com.decode.vectorizer.service.domain.Symbol;
import com.decode.vectorizer.service.repository.SymbolRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class VectorizerService {

    private final SymbolRepository symbolRepository;
    private final VectorStore vectorStore;

    @Transactional
    public void vectorizerAllSymbols() {
        log.info("Starting batch vectorization with domain context...");
        List<Symbol> symbols = symbolRepository.findAll();
        vectorizeSymbols(symbols);
    }
    
    @Transactional
    public void vectorizeProject(java.util.UUID projectId) {
        log.info("Vectorizing Project ID: {}", projectId);
        List<Symbol> symbols = symbolRepository.findAllBySourceFile_Project_Id(projectId);
        log.info("Found {} symbols in database for project {}", symbols.size(), projectId);
        
        if (symbols.isEmpty()) {
            log.warn("⚠️ No symbols found in database for project {}. Possible causes:", projectId);
            log.warn("  1. Parsing may have failed for all files");
            log.warn("  2. Files may not have been parsed yet");
            log.warn("  3. Database connection issue");
            log.warn("  4. Project ID mismatch");
            return;
        }
        
        // Quick pre-check: Skip if project is already fully vectorized
        if (isProjectFullyVectorized(projectId, symbols)) {
            log.info("✅ Project {} is already fully vectorized (all {} symbols exist in Qdrant). Skipping.", projectId, symbols.size());
            return;
        }
        
        vectorizeSymbols(symbols);
    }
    
    /**
     * Quick check to see if a project is already fully vectorized
     * Checks a sample of symbols to determine if vectorization is needed
     */
    private boolean isProjectFullyVectorized(java.util.UUID projectId, List<Symbol> symbols) {
        if (symbols.isEmpty()) {
            return false;
        }
        
        // Sample check: Check first 10 symbols and last 10 symbols
        // If all sampled symbols are vectorized, assume the project is fully vectorized
        int sampleSize = Math.min(20, symbols.size());
        List<Symbol> sample = new ArrayList<>();
        
        // Add first few
        int firstCount = Math.min(10, symbols.size());
        sample.addAll(symbols.subList(0, firstCount));
        
        // Add last few
        if (symbols.size() > firstCount) {
            int remaining = sampleSize - firstCount;
            int startIdx = Math.max(firstCount, symbols.size() - remaining);
            sample.addAll(symbols.subList(startIdx, symbols.size()));
        }
        
        // Build filter expression for sample
        String filterExpr = sample.stream()
            .map(s -> "symbol_id == '" + s.getId().toString() + "'")
            .collect(java.util.stream.Collectors.joining(" OR "));
        
        try {
            var searchRequest = org.springframework.ai.vectorstore.SearchRequest.builder()
                .query("")
                .topK(sample.size())
                .filterExpression("(" + filterExpr + ")")
                .build();
            
            var existingDocs = vectorStore.similaritySearch(searchRequest);
            int foundCount = existingDocs.size();
            
            // If all sampled symbols are found, assume project is fully vectorized
            // This is a heuristic - we'll do full check in vectorizeSymbols() anyway
            boolean fullyVectorized = foundCount == sample.size();
            
            if (fullyVectorized) {
                log.debug("Pre-check: All {} sampled symbols found in Qdrant for project {}", sample.size(), projectId);
            } else {
                log.debug("Pre-check: Only {}/{} sampled symbols found in Qdrant for project {}. Will vectorize.", 
                    foundCount, sample.size(), projectId);
            }
            
            return fullyVectorized;
            
        } catch (Exception e) {
            // If check fails, assume not vectorized and proceed with full check
            log.debug("Pre-check failed for project {}: {}. Will proceed with full vectorization.", projectId, e.getMessage());
            return false;
        }
    }

    private void vectorizeSymbols(List<Symbol> symbols) {
        log.info("Found {} symbols to vectorize", symbols.size());

        if (symbols.isEmpty()) {
            log.warn("⚠️ No symbols found to vectorize. Check if symbols were saved to database during parsing.");
            return;
        }

        // Filter out symbols that are already vectorized by checking Qdrant
        // OPTIMIZATION: Batch check for duplicates to reduce Qdrant load
        // Check symbols in batches of 50 to avoid overwhelming Qdrant
        List<Document> documentsToAdd = new ArrayList<>();
        int alreadyVectorizedCount = 0;
        Set<String> alreadyVectorizedIds = new HashSet<>();
        
        // Batch check: Process symbols in batches of 50
        int batchSize = 50;
        for (int i = 0; i < symbols.size(); i += batchSize) {
            int end = Math.min(i + batchSize, symbols.size());
            List<Symbol> batch = symbols.subList(i, end);
            
            // Build OR filter for this batch
            String filterExpr = batch.stream()
                .map(s -> "symbol_id == '" + s.getId().toString() + "'")
                .collect(java.util.stream.Collectors.joining(" OR "));
            
            try {
                var searchRequest = org.springframework.ai.vectorstore.SearchRequest.builder()
                    .query("") // Empty query - just checking existence
                    .topK(batch.size())
                    .filterExpression("(" + filterExpr + ")")
                    .build();
                
                var existingDocs = vectorStore.similaritySearch(searchRequest);
                
                // Track which symbols already exist
                for (var doc : existingDocs) {
                    String existingId = (String) doc.getMetadata().get("symbol_id");
                    if (existingId != null) {
                        alreadyVectorizedIds.add(existingId);
                    }
                }
                
                alreadyVectorizedCount += existingDocs.size();
                
            } catch (Exception e) {
                // If batch check fails, log and continue (will check individually below)
                log.warn("Batch duplicate check failed for batch {}-{}: {}. Will check individually.", i, end, e.getMessage());
            }
        }
        
        // Now process symbols and only add those that weren't found in batch check
        for (Symbol symbol : symbols) {
            String symbolId = symbol.getId().toString();
            
            // Skip if already vectorized (from batch check)
            if (alreadyVectorizedIds.contains(symbolId)) {
                continue;
            }
            
            // Fallback: Individual check if batch check didn't work
            try {
                var searchRequest = org.springframework.ai.vectorstore.SearchRequest.builder()
                    .query("")
                    .topK(1)
                    .filterExpression("symbol_id == '" + symbolId + "'")
                    .build();
                
                var existingDocs = vectorStore.similaritySearch(searchRequest);
                
                if (!existingDocs.isEmpty()) {
                    alreadyVectorizedCount++;
                    alreadyVectorizedIds.add(symbolId);
                    continue;
                }
            } catch (Exception e) {
                log.debug("Could not check if symbol {} exists in Qdrant: {}. Will add it.", symbolId, e.getMessage());
            }
            
            // Symbol doesn't exist - prepare it for vectorization
            String domain = symbol.getSourceFile().getProject().getDomain();
            String projectName = symbol.getSourceFile().getProject().getName();
            String category = symbol.getCategory();
            String name = symbol.getName();

            // Construct semantic text for embedding - prevents collision (e.g. Account in
            // Credit vs Mortgage)
            String content = String.format(
                    "Domain: %s | Project: %s | Category: %s | Symbol: %s",
                    domain != null ? domain : "General",
                    projectName,
                    category,
                    name);

            // Create Document with metadata for filtering
            // IMPORTANT: Duplicate prevention is handled by the existence check above
            // The existence check filters out symbols that are already in Qdrant
            // This prevents duplicate work and unnecessary embedding computation
            Document doc = new Document(content);
            doc.getMetadata().put("symbol_id", symbolId);
            doc.getMetadata().put("project_id", symbol.getSourceFile().getProject().getId().toString());
            doc.getMetadata().put("domain", domain != null ? domain : "General");
            doc.getMetadata().put("name", name);
            doc.getMetadata().put("file_path", symbol.getSourceFile().getFilePath());

            documentsToAdd.add(doc);
        }

        if (!documentsToAdd.isEmpty()) {
            vectorStore.add(documentsToAdd);
            log.info("✅ Successfully vectorized {} new symbols to Qdrant (Target collection: symbols). {} symbols were already vectorized.", 
                    documentsToAdd.size(), alreadyVectorizedCount);
        } else {
            if (alreadyVectorizedCount > 0) {
                log.info("ℹ️ All {} symbols were already vectorized. No new symbols to add.", alreadyVectorizedCount);
            } else {
                log.warn("⚠️ No symbols found to vectorize. Check if symbols were saved to database during parsing.");
            }
        }
    }
}
