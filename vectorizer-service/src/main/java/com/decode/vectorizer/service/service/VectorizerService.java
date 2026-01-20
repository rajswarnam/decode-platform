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
import java.util.List;

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
        }
        
        vectorizeSymbols(symbols);
    }

    private void vectorizeSymbols(List<Symbol> symbols) {
        log.info("Found {} symbols to vectorize", symbols.size());

        if (symbols.isEmpty()) {
            log.warn("⚠️ No symbols found to vectorize. Check if symbols were saved to database during parsing.");
            return;
        }

        // Filter out symbols that are already vectorized by checking Qdrant
        // Qdrant's add() method will create duplicates, so we need to check first
        List<Document> documentsToAdd = new ArrayList<>();
        int alreadyVectorizedCount = 0;
        
        for (Symbol symbol : symbols) {
            String symbolId = symbol.getId().toString();
            
            // Check if this symbol is already in Qdrant by searching for it using symbol_id metadata
            try {
                var searchRequest = org.springframework.ai.vectorstore.SearchRequest.builder()
                    .query("") // Empty query - we're just checking existence
                    .topK(1)
                    .filterExpression("symbol_id == '" + symbolId + "'")
                    .build();
                
                var existingDocs = vectorStore.similaritySearch(searchRequest);
                
                if (!existingDocs.isEmpty()) {
                    // Symbol already exists in Qdrant - skip it
                    alreadyVectorizedCount++;
                    continue;
                }
            } catch (Exception e) {
                // If filter/search fails, log and continue (might be first run or Qdrant issue)
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
            // IMPORTANT: Set document ID to symbol_id to prevent duplicates in Qdrant
            // Qdrant uses point IDs for uniqueness - same ID will overwrite, not create duplicate
            Document doc = new Document(symbolId, content); // Use symbolId as document ID
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
