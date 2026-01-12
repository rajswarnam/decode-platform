package com.decode.context.orchestrator.service;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Points.Filter;
import io.qdrant.client.grpc.Points.SearchPoints;
import io.qdrant.client.grpc.Points.ScoredPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for detecting code changes since a blueprint was created.
 * Queries Qdrant vector database to find new, modified, and deleted symbols.
 */
@Service
public class ChangeDetectionService {

    private static final Logger log = LoggerFactory.getLogger(ChangeDetectionService.class);

    @Autowired
    private QdrantClient qdrantClient;

    @Autowired
    private SemanticExplorerService semanticExplorerService;

    /**
     * Detect code changes since the blueprint was created.
     * 
     * @param project            Project name
     * @param originalQuery      The original query used to create the blueprint
     * @param blueprintTimestamp When the blueprint was created
     * @return ChangeReport containing new, modified, and deleted symbols
     */
    public ChangeReport detectChanges(String project, String originalQuery, LocalDateTime blueprintTimestamp) {
        try {
            log.info("Detecting code changes for project: {}, since: {}", project, blueprintTimestamp);

            // 1. Get current symbols related to the original query
            List<CodeSymbol> currentSymbols = fetchCurrentSymbols(project, originalQuery);

            // 2. Filter symbols by timestamp to identify new and modified
            Instant blueprintInstant = blueprintTimestamp.atZone(ZoneId.systemDefault()).toInstant();

            List<CodeSymbol> newSymbols = currentSymbols.stream()
                    .filter(s -> s.getIndexedAt() != null && s.getIndexedAt().isAfter(blueprintInstant))
                    .collect(Collectors.toList());

            List<CodeSymbol> modifiedSymbols = currentSymbols.stream()
                    .filter(s -> s.getLastModified() != null &&
                            s.getLastModified().isAfter(blueprintInstant) &&
                            (s.getIndexedAt() == null || s.getIndexedAt().isBefore(blueprintInstant)))
                    .collect(Collectors.toList());

            // 3. For deleted symbols, we'd need to compare against stored original symbols
            // For now, we'll leave this empty (requires storing original symbol list in
            // metadata)
            List<CodeSymbol> deletedSymbols = new ArrayList<>();

            log.info("Change detection complete: {} new, {} modified, {} deleted symbols",
                    newSymbols.size(), modifiedSymbols.size(), deletedSymbols.size());

            return new ChangeReport(newSymbols, modifiedSymbols, deletedSymbols);

        } catch (Exception e) {
            log.error("Error detecting code changes", e);
            return new ChangeReport(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }
    }

    /**
     * Fetch current symbols from Qdrant related to the query.
     */
    private List<CodeSymbol> fetchCurrentSymbols(String project, String query) {
        try {
            // Use the same search logic as SemanticExplorerService
            // This is a simplified version - in production, we'd want to reuse the exact
            // same logic

            List<CodeSymbol> symbols = new ArrayList<>();

            // TODO: Implement actual Qdrant search
            // For now, return empty list to avoid compilation errors
            // In the full implementation, this would:
            // 1. Generate embedding for the query
            // 2. Search Qdrant with project filter
            // 3. Parse results into CodeSymbol objects

            return symbols;

        } catch (Exception e) {
            log.error("Error fetching current symbols from Qdrant", e);
            return new ArrayList<>();
        }
    }

    /**
     * Represents a code symbol with metadata.
     */
    public static class CodeSymbol {
        private String name;
        private String type;
        private String filePath;
        private String content;
        private Instant indexedAt;
        private Instant lastModified;
        private Map<String, Object> metadata;

        public CodeSymbol(String name, String type, String filePath, String content) {
            this.name = name;
            this.type = type;
            this.filePath = filePath;
            this.content = content;
            this.metadata = new HashMap<>();
        }

        // Getters and Setters
        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getFilePath() {
            return filePath;
        }

        public void setFilePath(String filePath) {
            this.filePath = filePath;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public Instant getIndexedAt() {
            return indexedAt;
        }

        public void setIndexedAt(Instant indexedAt) {
            this.indexedAt = indexedAt;
        }

        public Instant getLastModified() {
            return lastModified;
        }

        public void setLastModified(Instant lastModified) {
            this.lastModified = lastModified;
        }

        public Map<String, Object> getMetadata() {
            return metadata;
        }

        public void setMetadata(Map<String, Object> metadata) {
            this.metadata = metadata;
        }
    }

    /**
     * Report of code changes detected.
     */
    public static class ChangeReport {
        private List<CodeSymbol> newSymbols;
        private List<CodeSymbol> modifiedSymbols;
        private List<CodeSymbol> deletedSymbols;

        public ChangeReport(List<CodeSymbol> newSymbols, List<CodeSymbol> modifiedSymbols,
                List<CodeSymbol> deletedSymbols) {
            this.newSymbols = newSymbols;
            this.modifiedSymbols = modifiedSymbols;
            this.deletedSymbols = deletedSymbols;
        }

        public boolean hasChanges() {
            return !newSymbols.isEmpty() || !modifiedSymbols.isEmpty() || !deletedSymbols.isEmpty();
        }

        public String getSummary() {
            if (!hasChanges()) {
                return "No code changes detected";
            }

            List<String> parts = new ArrayList<>();
            if (!newSymbols.isEmpty()) {
                parts.add(newSymbols.size() + " new symbol(s)");
            }
            if (!modifiedSymbols.isEmpty()) {
                parts.add(modifiedSymbols.size() + " modified symbol(s)");
            }
            if (!deletedSymbols.isEmpty()) {
                parts.add(deletedSymbols.size() + " deleted symbol(s)");
            }

            return String.join(", ", parts);
        }

        public String toMarkdown() {
            if (!hasChanges()) {
                return "**No code changes detected since blueprint creation.**\n";
            }

            StringBuilder md = new StringBuilder();
            md.append("### Code Changes Detected\n\n");

            if (!newSymbols.isEmpty()) {
                md.append("**✨ New Symbols:**\n");
                for (CodeSymbol symbol : newSymbols) {
                    md.append(String.format("- `%s` (%s) in `%s`\n", symbol.getName(), symbol.getType(),
                            symbol.getFilePath()));
                }
                md.append("\n");
            }

            if (!modifiedSymbols.isEmpty()) {
                md.append("**🔄 Modified Symbols:**\n");
                for (CodeSymbol symbol : modifiedSymbols) {
                    md.append(String.format("- `%s` (%s) in `%s`\n", symbol.getName(), symbol.getType(),
                            symbol.getFilePath()));
                }
                md.append("\n");
            }

            if (!deletedSymbols.isEmpty()) {
                md.append("**❌ Deleted Symbols:**\n");
                for (CodeSymbol symbol : deletedSymbols) {
                    md.append(String.format("- `%s` (%s) in `%s`\n", symbol.getName(), symbol.getType(),
                            symbol.getFilePath()));
                }
                md.append("\n");
            }

            return md.toString();
        }

        // Getters
        public List<CodeSymbol> getNewSymbols() {
            return newSymbols;
        }

        public List<CodeSymbol> getModifiedSymbols() {
            return modifiedSymbols;
        }

        public List<CodeSymbol> getDeletedSymbols() {
            return deletedSymbols;
        }

        public int getNewSymbolsCount() {
            return newSymbols.size();
        }

        public int getModifiedSymbolsCount() {
            return modifiedSymbols.size();
        }

        public int getDeletedSymbolsCount() {
            return deletedSymbols.size();
        }
    }
}
