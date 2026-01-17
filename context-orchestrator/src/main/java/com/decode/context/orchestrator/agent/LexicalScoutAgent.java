package com.decode.context.orchestrator.agent;

import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Lexical Scout Agent - The "Domain Discovery" phase
 * 
 * Runs BEFORE the Head Architect to discover the domain vocabulary of a "blind" project.
 * Analyzes vector store metadata to identify:
 * - High-frequency business entities (Order, Invoice, Product, Partner)
 * - Module structure (procurement, sales, inventory)
 * - Entity relationships (Order → OrderLine)
 * - Domain patterns (Order-to-Cash, Procure-to-Pay)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LexicalScoutAgent {

    private final VectorStore vectorStore;
    
    // Cache domain maps per project (domain name -> domain map)
    // In production, use Redis or database for persistence
    private final Map<String, CachedDomainMap> domainCache = new java.util.concurrent.ConcurrentHashMap<>();
    
    @Data
    @Builder
    private static class CachedDomainMap {
        private DomainMap domainMap;
        private long timestamp;
        private int vectorCount; // Track vector count to detect re-ingestion
    }
    
    @Data
    @Builder
    public static class DomainMap {
        private List<BusinessEntity> topEntities;
        private List<ModuleCluster> modules;
        private List<String> domainPatterns;
        private Map<String, Integer> nounFrequency;
        private String domainSummary;
    }
    
    @Data
    @Builder
    public static class BusinessEntity {
        private String name;
        private int frequency;
        private String category; // "Aggregate Root", "Value Object", "Service"
        private List<String> relatedEntities;
        private List<String> sampleFiles;
    }
    
    @Data
    @Builder
    public static class ModuleCluster {
        private String moduleName;
        private int fileCount;
        private List<String> primaryEntities;
        private String inferredPurpose;
    }
    
    /**
     * Perform domain discovery by analyzing vector store metadata
     * Uses cache to avoid re-analyzing the same project
     */
    public DomainMap discoverDomain(String projectContext, Consumer<String> progressConsumer) {
        // Extract domain/project identifier from context
        String domain = extractDomainIdentifier(projectContext);
        
        // Check cache first
        CachedDomainMap cached = domainCache.get(domain);
        if (cached != null) {
            // Check if cache is still valid (less than 1 hour old)
            long age = System.currentTimeMillis() - cached.getTimestamp();
            if (age < 3600000) { // 1 hour TTL
                log.info("🔍 Lexical Scout: Using cached domain map for '{}' (age: {}ms)", domain, age);
                if (progressConsumer != null) progressConsumer.accept("🔍 Lexical Scout: Using cached domain map");
                return cached.getDomainMap();
            } else {
                log.info("🔍 Lexical Scout: Cache expired for '{}', re-discovering...", domain);
            }
        }
        
        log.info("🔍 Lexical Scout: Beginning domain discovery for '{}'...", domain);
        if (progressConsumer != null) progressConsumer.accept("🔍 Lexical Scout: Scanning codebase for domain vocabulary...");
        
        // Sample the vector store to get a representative set of documents
        List<Document> sampleDocs = sampleVectorStore(500);
        
        if (sampleDocs.isEmpty()) {
            log.warn("No documents found in vector store. Domain discovery skipped.");
            return DomainMap.builder()
                .topEntities(List.of())
                .modules(List.of())
                .domainPatterns(List.of())
                .nounFrequency(Map.of())
                .domainSummary("No code found in vector store. Project may not be ingested yet.")
                .build();
        }
        
        log.info("Sampled {} documents for domain analysis", sampleDocs.size());
        if (progressConsumer != null) progressConsumer.accept("📊 Analyzing " + sampleDocs.size() + " code symbols...");
        
        // Extract nouns from symbol names and file paths
        Map<String, Integer> nounFrequency = extractNouns(sampleDocs);
        
        // Identify top business entities
        List<BusinessEntity> topEntities = identifyBusinessEntities(nounFrequency, sampleDocs);
        
        // Cluster by modules
        List<ModuleCluster> modules = clusterByModules(sampleDocs, topEntities);
        
        // Identify domain patterns
        List<String> domainPatterns = identifyDomainPatterns(topEntities, modules);
        
        // Generate summary
        String summary = generateDomainSummary(topEntities, modules, domainPatterns);
        
        log.info("Domain discovery complete. Found {} entities across {} modules", 
            topEntities.size(), modules.size());
        if (progressConsumer != null) progressConsumer.accept("✅ Domain map created: " + topEntities.size() + " entities, " + modules.size() + " modules");
        
        DomainMap domainMap = DomainMap.builder()
            .topEntities(topEntities)
            .modules(modules)
            .domainPatterns(domainPatterns)
            .nounFrequency(nounFrequency)
            .domainSummary(summary)
            .build();
        
        // Cache the result
        domainCache.put(domain, CachedDomainMap.builder()
            .domainMap(domainMap)
            .timestamp(System.currentTimeMillis())
            .vectorCount(sampleDocs.size())
            .build());
        
        log.info("🔍 Lexical Scout: Cached domain map for '{}'", domain);
        
        return domainMap;
    }
    
    /**
     * Extract domain identifier from project context
     * Looks for "Domain: xxx" pattern or uses "default"
     */
    private String extractDomainIdentifier(String projectContext) {
        if (projectContext == null) return "default";
        
        // Look for "Domain: xxx" pattern
        int domainIndex = projectContext.indexOf("Domain:");
        if (domainIndex != -1) {
            int endIndex = projectContext.indexOf("|", domainIndex);
            if (endIndex == -1) endIndex = projectContext.indexOf("\n", domainIndex);
            if (endIndex == -1) endIndex = Math.min(domainIndex + 50, projectContext.length());
            
            String domain = projectContext.substring(domainIndex + 7, endIndex).trim();
            return domain.isEmpty() ? "default" : domain;
        }
        
        return "default";
    }
    
    private List<Document> sampleVectorStore(int sampleSize) {
        // Stratified Sampling: Query multiple layers to ensure diverse coverage
        List<String> strataQueries = List.of(
            "service controller repository",      // Backend Layer
            "component page state props hook",    // Frontend Layer
            "table schema column primary key",    // Database Layer
            "business rule logic calculation",    // Core Logic
            "config yaml properties auth"         // Infrastructure
        );
        
        int perStrata = sampleSize / strataQueries.size();
        Set<Document> combinedDocs = new HashSet<>();
        
        for (String query : strataQueries) {
            SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(perStrata)
                .build();
            combinedDocs.addAll(vectorStore.similaritySearch(request));
        }
        
        return new ArrayList<>(combinedDocs);
    }
    
    private Map<String, Integer> extractNouns(List<Document> docs) {
        Map<String, Integer> frequency = new HashMap<>();
        
        // Pattern to extract camelCase and PascalCase nouns
        Pattern nounPattern = Pattern.compile("([A-Z][a-z]+)");
        
        for (Document doc : docs) {
            String name = (String) doc.getMetadata().getOrDefault("name", "");
            String filePath = (String) doc.getMetadata().getOrDefault("file_path", "");
            
            // Extract from symbol name
            Matcher matcher = nounPattern.matcher(name);
            while (matcher.find()) {
                String noun = matcher.group(1);
                if (noun.length() > 2 && !isCommonTechTerm(noun)) {
                    frequency.merge(noun, 1, Integer::sum);
                }
            }
            
            // Extract from file path (package names, etc.)
            matcher = nounPattern.matcher(filePath);
            while (matcher.find()) {
                String noun = matcher.group(1);
                if (noun.length() > 2 && !isCommonTechTerm(noun)) {
                    frequency.merge(noun, 1, Integer::sum);
                }
            }
        }
        
        return frequency;
    }
    
    private boolean isCommonTechTerm(String word) {
        Set<String> techTerms = Set.of(
            "Service", "Controller", "Repository", "Entity", "Model", "Dto", "Request", "Response",
            "Manager", "Handler", "Processor", "Factory", "Builder", "Util", "Helper", "Config",
            "Component", "Bean", "Autowired", "Inject", "Value", "Data", "Getter", "Setter",
            "Override", "Deprecated", "Nullable", "Nonnull", "Generated", "Transactional"
        );
        return techTerms.contains(word);
    }
    
    private List<BusinessEntity> identifyBusinessEntities(Map<String, Integer> nounFrequency, List<Document> docs) {
        // Sort by frequency and take top 20
        List<Map.Entry<String, Integer>> sorted = nounFrequency.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(20)
            .collect(Collectors.toList());
        
        List<BusinessEntity> entities = new ArrayList<>();
        
        for (Map.Entry<String, Integer> entry : sorted) {
            String entityName = entry.getKey();
            int frequency = entry.getValue();
            
            // Find sample files containing this entity
            List<String> sampleFiles = docs.stream()
                .filter(d -> d.getMetadata().getOrDefault("name", "").toString().contains(entityName))
                .map(d -> d.getMetadata().getOrDefault("file_path", "").toString())
                .distinct()
                .limit(3)
                .collect(Collectors.toList());
            
            // Infer category based on naming patterns
            String category = inferCategory(entityName, sampleFiles);
            
            // Find related entities (entities that appear in same files)
            List<String> related = findRelatedEntities(entityName, docs, nounFrequency.keySet());
            
            entities.add(BusinessEntity.builder()
                .name(entityName)
                .frequency(frequency)
                .category(category)
                .relatedEntities(related)
                .sampleFiles(sampleFiles)
                .build());
        }
        
        return entities;
    }
    
    private String inferCategory(String entityName, List<String> sampleFiles) {
        // Heuristics to categorize entities
        if (sampleFiles.stream().anyMatch(f -> f.contains("Repository") || f.contains("Dao"))) {
            return "Aggregate Root";
        } else if (sampleFiles.stream().anyMatch(f -> f.contains("Service"))) {
            return "Domain Service";
        } else if (entityName.endsWith("Line") || entityName.endsWith("Item") || entityName.endsWith("Detail")) {
            return "Value Object";
        } else if (sampleFiles.stream().anyMatch(f -> f.contains("Controller") || f.contains("Resource"))) {
            return "API Entity";
        }
        return "Business Entity";
    }
    
    private List<String> findRelatedEntities(String entityName, List<Document> docs, Set<String> allNouns) {
        // Find entities that co-occur in the same files
        Set<String> relatedFiles = docs.stream()
            .filter(d -> d.getMetadata().getOrDefault("name", "").toString().contains(entityName))
            .map(d -> d.getMetadata().getOrDefault("file_path", "").toString())
            .collect(Collectors.toSet());
        
        Map<String, Integer> coOccurrence = new HashMap<>();
        for (String noun : allNouns) {
            if (noun.equals(entityName)) continue;
            
            long count = docs.stream()
                .filter(d -> relatedFiles.contains(d.getMetadata().getOrDefault("file_path", "")))
                .filter(d -> d.getMetadata().getOrDefault("name", "").toString().contains(noun))
                .count();
            
            if (count > 0) {
                coOccurrence.put(noun, (int) count);
            }
        }
        
        return coOccurrence.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(5)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    }
    
    private List<ModuleCluster> clusterByModules(List<Document> docs, List<BusinessEntity> entities) {
        // Extract module names from file paths
        Map<String, List<Document>> moduleGroups = new HashMap<>();
        
        for (Document doc : docs) {
            String filePath = doc.getMetadata().getOrDefault("file_path", "").toString();
            String module = extractModuleName(filePath);
            moduleGroups.computeIfAbsent(module, k -> new ArrayList<>()).add(doc);
        }
        
        List<ModuleCluster> clusters = new ArrayList<>();
        
        for (Map.Entry<String, List<Document>> entry : moduleGroups.entrySet()) {
            String moduleName = entry.getKey();
            List<Document> moduleDocs = entry.getValue();
            
            // Find primary entities in this module
            List<String> primaryEntities = entities.stream()
                .filter(e -> moduleDocs.stream()
                    .anyMatch(d -> d.getMetadata().getOrDefault("name", "").toString().contains(e.getName())))
                .map(BusinessEntity::getName)
                .limit(3)
                .collect(Collectors.toList());
            
            // Infer purpose from module name and entities
            String purpose = inferModulePurpose(moduleName, primaryEntities);
            
            clusters.add(ModuleCluster.builder()
                .moduleName(moduleName)
                .fileCount(moduleDocs.size())
                .primaryEntities(primaryEntities)
                .inferredPurpose(purpose)
                .build());
        }
        
        return clusters.stream()
            .sorted(Comparator.comparingInt(ModuleCluster::getFileCount).reversed())
            .limit(10)
            .collect(Collectors.toList());
    }
    
    private String extractModuleName(String filePath) {
        // Extract module from path like "project-id/module-name/src/..."
        String[] parts = filePath.split("/");
        if (parts.length > 1) {
            return parts[1]; // Second part is usually the module name
        }
        return "unknown";
    }
    
    private String inferModulePurpose(String moduleName, List<String> entities) {
        String lower = moduleName.toLowerCase();
        
        if (lower.contains("procurement") || lower.contains("purchase")) {
            return "Procurement & Purchasing";
        } else if (lower.contains("sales") || lower.contains("order")) {
            return "Sales & Order Management";
        } else if (lower.contains("inventory") || lower.contains("stock")) {
            return "Inventory Management";
        } else if (lower.contains("finance") || lower.contains("accounting")) {
            return "Financial Management";
        } else if (lower.contains("webui") || lower.contains("frontend")) {
            return "User Interface";
        } else if (lower.contains("backend") || lower.contains("api")) {
            return "Backend Services";
        } else if (!entities.isEmpty()) {
            return entities.get(0) + " Management";
        }
        
        return "General Module";
    }
    
    private List<String> identifyDomainPatterns(List<BusinessEntity> entities, List<ModuleCluster> modules) {
        List<String> patterns = new ArrayList<>();
        
        // Look for common ERP patterns
        Set<String> entityNames = entities.stream().map(BusinessEntity::getName).collect(Collectors.toSet());
        
        if (entityNames.contains("Order") && entityNames.contains("Invoice")) {
            patterns.add("Order-to-Cash");
        }
        if (entityNames.contains("Purchase") && entityNames.contains("Receipt")) {
            patterns.add("Procure-to-Pay");
        }
        if (entityNames.contains("Product") && entityNames.contains("Inventory")) {
            patterns.add("Inventory Management");
        }
        if (entityNames.contains("Customer") || entityNames.contains("Partner")) {
            patterns.add("Customer Relationship Management");
        }
        if (entityNames.contains("Shipment") || entityNames.contains("Delivery")) {
            patterns.add("Logistics & Distribution");
        }
        
        return patterns;
    }
    
    private String generateDomainSummary(List<BusinessEntity> entities, List<ModuleCluster> modules, List<String> patterns) {
        StringBuilder summary = new StringBuilder();
        
        summary.append("This is a ").append(inferSystemType(patterns)).append(" system.\n\n");
        
        summary.append("Core Business Entities:\n");
        entities.stream().limit(5).forEach(e -> 
            summary.append("- ").append(e.getName())
                .append(" (").append(e.getCategory()).append(", frequency: ").append(e.getFrequency()).append(")\n")
        );
        
        summary.append("\nKey Modules:\n");
        modules.stream().limit(5).forEach(m ->
            summary.append("- ").append(m.getModuleName())
                .append(": ").append(m.getInferredPurpose())
                .append(" (").append(m.getFileCount()).append(" files)\n")
        );
        
        if (!patterns.isEmpty()) {
            summary.append("\nIdentified Domain Patterns:\n");
            patterns.forEach(p -> summary.append("- ").append(p).append("\n"));
        }
        
        return summary.toString();
    }
    
    private String inferSystemType(List<String> patterns) {
        if (patterns.contains("Order-to-Cash") && patterns.contains("Procure-to-Pay")) {
            return "Enterprise Resource Planning (ERP)";
        } else if (patterns.contains("Order-to-Cash")) {
            return "Sales & Order Management";
        } else if (patterns.contains("Procure-to-Pay")) {
            return "Procurement Management";
        } else if (patterns.contains("Customer Relationship Management")) {
            return "CRM";
        }
        return "Business Management";
    }
}
