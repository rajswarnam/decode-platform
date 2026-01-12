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
        log.info("Found {} symbols to vectorize", symbols.size());

        List<Document> documents = new ArrayList<>();
        for (Symbol symbol : symbols) {
            String domain = symbol.getSourceFile().getProject().getDomain();
            String projectName = symbol.getSourceFile().getProject().getName();
            String category = symbol.getCategory();
            String name = symbol.getName();

            // Construct semantic text for embedding - prevens collision (e.g. Account in
            // Credit vs Mortgage)
            String content = String.format(
                    "Domain: %s | Project: %s | Category: %s | Symbol: %s",
                    domain != null ? domain : "General",
                    projectName,
                    category,
                    name);

            // Create Document with metadata for filtering
            Document doc = new Document(content);
            doc.getMetadata().put("symbol_id", symbol.getId().toString());
            doc.getMetadata().put("project_id", symbol.getSourceFile().getProject().getId().toString());
            doc.getMetadata().put("domain", domain != null ? domain : "General");
            doc.getMetadata().put("name", name);
            doc.getMetadata().put("file_path", symbol.getSourceFile().getFilePath());

            documents.add(doc);
        }

        if (!documents.isEmpty()) {
            vectorStore.add(documents);
            log.info("Successfully vectorized {} symbols to Qdrant (Target collection: symbols)", documents.size());
        } else {
            log.warn("No symbols found to vectorize.");
        }
    }
}
