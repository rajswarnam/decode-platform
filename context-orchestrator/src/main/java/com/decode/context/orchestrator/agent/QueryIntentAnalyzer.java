package com.decode.context.orchestrator.agent;

import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Query Intent Analyzer
 * 
 * Detects whether a query needs comprehensive coverage (all/entire/everything) 
 * or focused analysis (specific topics)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QueryIntentAnalyzer {
    
    // Patterns that indicate comprehensive intent
    private static final List<Pattern> COMPREHENSIVE_PATTERNS = List.of(
        Pattern.compile(".*\\b(all|entire|every|complete|comprehensive|full|overview|everything|whole|complete).*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*\\b(what are|list all|show all|all the|all of the|what are the).*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*\\b(summarize|summarise|give me an overview|provide overview|overview of).*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*\\b(all business|all use case|all functionality|all features|all services).*", Pattern.CASE_INSENSITIVE)
    );
    
    // Patterns that indicate focused intent
    private static final List<Pattern> FOCUSED_PATTERNS = List.of(
        Pattern.compile(".*\\b(how does|how do|explain|describe|detail|specific|particular|exactly).*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*\\b(show me|find|where is|where does|locate).*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*\\b(implementation|code for|class|method|function).*", Pattern.CASE_INSENSITIVE)
    );
    
    @Data
    @Builder
    public static class QueryIntent {
        public enum Mode {
            COMPREHENSIVE,  // Need broad coverage (200-500 files)
            FOCUSED,        // Need deep, relevant analysis (50-100 files)
            HYBRID          // Balanced approach (100-200 files)
        }
        
        private Mode mode;
        private int recommendedTopK;
        private boolean useModuleSampling;  // Sample from each module
        private boolean useStratifiedSearch; // Multiple search queries
        private String reasoning;
    }
    
    /**
     * Analyze query intent and recommend search strategy
     */
    public QueryIntent analyzeIntent(String userQuery, LexicalScoutAgent.DomainMap domainMap) {
        String queryLower = userQuery.toLowerCase();
        
        // Check for comprehensive patterns
        boolean isComprehensive = COMPREHENSIVE_PATTERNS.stream()
            .anyMatch(pattern -> pattern.matcher(queryLower).matches());
        
        // Check for focused patterns
        boolean isFocused = FOCUSED_PATTERNS.stream()
            .anyMatch(pattern -> pattern.matcher(queryLower).matches());
        
        // If both or neither, use hybrid
        QueryIntent.Mode mode;
        int recommendedTopK;
        boolean useModuleSampling;
        boolean useStratifiedSearch;
        String reasoning;
        
        if (isComprehensive && !isFocused) {
            mode = QueryIntent.Mode.COMPREHENSIVE;
            recommendedTopK = 200; // Higher topK for broad coverage
            useModuleSampling = true; // Sample from each module
            useStratifiedSearch = true; // Multiple search strategies
            reasoning = "Query requests comprehensive/all coverage - using broad search with module sampling";
        } else if (isFocused && !isComprehensive) {
            mode = QueryIntent.Mode.FOCUSED;
            recommendedTopK = 50; // Standard topK for relevance
            useModuleSampling = false; // No module sampling needed
            useStratifiedSearch = false; // Single semantic search
            reasoning = "Query requests specific/focused analysis - using targeted semantic search";
        } else {
            // HYBRID: Mix of both or ambiguous
            mode = QueryIntent.Mode.HYBRID;
            recommendedTopK = 100; // Moderate topK
            useModuleSampling = true; // Light module sampling
            useStratifiedSearch = true; // Fewer strata than comprehensive
            reasoning = "Query has mixed intent - using balanced approach with light module sampling";
        }
        
        log.info("Query Intent Analysis: mode={}, topK={}, moduleSampling={}, stratified={}, reasoning={}", 
            mode, recommendedTopK, useModuleSampling, useStratifiedSearch, reasoning);
        
        return QueryIntent.builder()
            .mode(mode)
            .recommendedTopK(recommendedTopK)
            .useModuleSampling(useModuleSampling)
            .useStratifiedSearch(useStratifiedSearch)
            .reasoning(reasoning)
            .build();
    }
}
