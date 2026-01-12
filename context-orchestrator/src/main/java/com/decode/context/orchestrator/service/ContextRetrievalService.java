package com.decode.context.orchestrator.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for retrieving enhanced context for blueprint refinement.
 * Fetches original context, additional context based on refinement prompt,
 * and combines with detected code changes.
 */
@Service
public class ContextRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(ContextRetrievalService.class);

    @Autowired
    private SemanticExplorerService semanticExplorerService;

    /**
     * Fetch enhanced context for blueprint refinement.
     * 
     * @param project          Project name
     * @param originalQuery    The original query used to create the blueprint
     * @param refinementPrompt The user's refinement request
     * @param changes          Code changes detected since blueprint creation
     * @return EnhancedContext containing all relevant code symbols
     */
    public EnhancedContext fetchContext(
            String project,
            String originalQuery,
            String refinementPrompt,
            ChangeDetectionService.ChangeReport changes) {

        try {
            log.info("Fetching enhanced context for refinement: {}", refinementPrompt);

            // 1. Extract keywords from refinement prompt
            List<String> keywords = extractKeywords(refinementPrompt);
            log.info("Extracted keywords: {}", keywords);

            // 2. Fetch additional context based on keywords
            // For example, if prompt mentions "payload", prioritize DTO/model classes
            Set<ChangeDetectionService.CodeSymbol> additionalSymbols = new HashSet<>();

            for (String keyword : keywords) {
                // TODO: Implement actual Qdrant search for each keyword
                // This would search for symbols matching the keyword
                // and prioritize certain types (e.g., DTOs for "payload", services for
                // "integration")
            }

            // 3. Combine all symbols and deduplicate
            Set<ChangeDetectionService.CodeSymbol> allSymbols = new HashSet<>();
            allSymbols.addAll(additionalSymbols);
            allSymbols.addAll(changes.getNewSymbols());
            allSymbols.addAll(changes.getModifiedSymbols());

            log.info("Enhanced context retrieved: {} total symbols", allSymbols.size());

            return new EnhancedContext(
                    new ArrayList<>(additionalSymbols),
                    changes,
                    keywords);

        } catch (Exception e) {
            log.error("Error fetching enhanced context", e);
            return new EnhancedContext(new ArrayList<>(), changes, new ArrayList<>());
        }
    }

    /**
     * Extract keywords from refinement prompt.
     * Identifies important terms that indicate what additional context to fetch.
     */
    private List<String> extractKeywords(String refinementPrompt) {
        // Simple keyword extraction - in production, this could use NLP
        String lowerPrompt = refinementPrompt.toLowerCase();

        List<String> keywords = new ArrayList<>();

        // Technical keywords that indicate specific types of code to look for
        Map<String, List<String>> keywordMappings = Map.of(
                "payload", List.of("DTO", "Request", "Response", "Model"),
                "schema", List.of("Entity", "Table", "Model", "DTO"),
                "api", List.of("Controller", "Endpoint", "Rest", "API"),
                "service", List.of("Service", "Business", "Logic"),
                "integration", List.of("Client", "Integration", "External", "API"),
                "security", List.of("Security", "Auth", "Permission", "Role"),
                "validation", List.of("Validator", "Validation", "Constraint"),
                "error", List.of("Exception", "Error", "Handler"));

        for (Map.Entry<String, List<String>> entry : keywordMappings.entrySet()) {
            if (lowerPrompt.contains(entry.getKey())) {
                keywords.addAll(entry.getValue());
            }
        }

        // If no specific keywords found, use the refinement prompt itself
        if (keywords.isEmpty()) {
            keywords.add(refinementPrompt);
        }

        return keywords.stream().distinct().collect(Collectors.toList());
    }

    /**
     * Enhanced context containing all relevant code symbols for refinement.
     */
    public static class EnhancedContext {
        private List<ChangeDetectionService.CodeSymbol> additionalSymbols;
        private ChangeDetectionService.ChangeReport changes;
        private List<String> keywords;

        public EnhancedContext(
                List<ChangeDetectionService.CodeSymbol> additionalSymbols,
                ChangeDetectionService.ChangeReport changes,
                List<String> keywords) {
            this.additionalSymbols = additionalSymbols;
            this.changes = changes;
            this.keywords = keywords;
        }

        public String toMarkdown() {
            StringBuilder md = new StringBuilder();

            // Add code changes
            if (changes.hasChanges()) {
                md.append(changes.toMarkdown());
            }

            // Add additional context
            if (!additionalSymbols.isEmpty()) {
                md.append("### Additional Context Retrieved\n\n");
                md.append(String.format("Found %d relevant symbols based on keywords: %s\n\n",
                        additionalSymbols.size(),
                        String.join(", ", keywords)));

                // Group by type
                Map<String, List<ChangeDetectionService.CodeSymbol>> byType = additionalSymbols.stream()
                        .collect(Collectors.groupingBy(ChangeDetectionService.CodeSymbol::getType));

                for (Map.Entry<String, List<ChangeDetectionService.CodeSymbol>> entry : byType.entrySet()) {
                    md.append(String.format("**%s (%d):**\n", entry.getKey(), entry.getValue().size()));
                    for (ChangeDetectionService.CodeSymbol symbol : entry.getValue()) {
                        md.append(String.format("- `%s` in `%s`\n", symbol.getName(), symbol.getFilePath()));
                    }
                    md.append("\n");
                }
            }

            // Add code snippets
            if (!additionalSymbols.isEmpty()) {
                md.append("### Relevant Code Snippets\n\n");
                for (ChangeDetectionService.CodeSymbol symbol : additionalSymbols) {
                    if (symbol.getContent() != null && !symbol.getContent().isEmpty()) {
                        md.append(String.format("**%s** (`%s`):\n", symbol.getName(), symbol.getFilePath()));
                        md.append("```java\n");
                        md.append(symbol.getContent());
                        md.append("\n```\n\n");
                    }
                }
            }

            return md.toString();
        }

        // Getters
        public List<ChangeDetectionService.CodeSymbol> getAdditionalSymbols() {
            return additionalSymbols;
        }

        public ChangeDetectionService.ChangeReport getChanges() {
            return changes;
        }

        public List<String> getKeywords() {
            return keywords;
        }

        public int getTotalSymbolsCount() {
            return additionalSymbols.size() +
                    changes.getNewSymbolsCount() +
                    changes.getModifiedSymbolsCount();
        }
    }
}
