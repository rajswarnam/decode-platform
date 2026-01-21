package com.decode.context.orchestrator.service;

import com.decode.context.orchestrator.domain.Symbol;
import com.decode.context.orchestrator.repository.ProjectRepository;
import com.decode.context.orchestrator.repository.SourceFileRepository;
import com.decode.context.orchestrator.agent.AgentOrchestrator;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.stream.Collectors;
import com.decode.context.orchestrator.repository.SymbolRepository;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SemanticExplorerService {

    private final VectorStore vectorStore;
    private final ChatClient.Builder chatClientBuilder;
    private final MinioClient minioClient;
    private final AgentOrchestrator agentOrchestrator;
    private final SymbolRepository symbolRepository;

    private final ProjectRepository projectRepository;
    private final SourceFileRepository sourceFileRepository;

    @Value("${minio.bucket}")
    private String bucket;

    private static final int MAX_CONTEXT_CHARS = 75000;

    public void exploreStream(String query, String domain, Consumer<String> progressConsumer,
            Consumer<String> answerChunkConsumer) {
        exploreStream(query, domain, Collections.emptyList(), progressConsumer, answerChunkConsumer);
    }
    
    public void exploreStream(String query, String domain, List<String> projectNames, Consumer<String> progressConsumer,
            Consumer<String> answerChunkConsumer) {
        exploreStream(query, domain, projectNames, Collections.emptyList(), progressConsumer, answerChunkConsumer);
    }
    
    public void exploreStream(String query, String domain, List<String> projectNames, List<UUID> projectIds, Consumer<String> progressConsumer,
            Consumer<String> answerChunkConsumer) {
        log.info("Executing Discovery: '{}'", query);
        
        String queryLower = query.toLowerCase();
        
        // EXPANDED REGEX: Detect queries that need domain-aware analysis
        // Includes: blueprint keywords, business/domain questions, "what/explain/tell me about" queries
        boolean needsAgentSwarm = queryLower.matches(".*(blueprint|re-?design|re-?build|spec|architect|overview|analysis|map|structure|comprehensive|full|brd|requirements|documentation).*")
            || queryLower.matches(".*(what|explain|tell me|describe|show me|help me understand).*")
            || queryLower.matches(".*(business|usecase|use case|use-case|functionality|purpose|addresses|handles).*")
            || queryLower.matches(".*(new to|getting started|introduction|overview of).*")
            || (domain != null && !domain.equalsIgnoreCase("General")); // Always use swarm for domain-specific queries

        if (progressConsumer != null)
            progressConsumer.accept(needsAgentSwarm ? "Initiating Multi-Agent Analysis..." : "Scanning Codebase...");

        StringBuilder contextBuilder = new StringBuilder();

        // 1. META-CONTEXT INJECTION & AGENT SWARM (For domain-aware queries)
        if (needsAgentSwarm) {
            progressConsumer.accept("Initiating Decode Protocol: Multi-Agent Swarm Activation...");
            injectProjectStructure(contextBuilder, domain, projectNames, progressConsumer);
            
            // Delegate to Agent Orchestrator (includes Lexical Scout + Workers + QA)
            // Pass domain for project filtering
            agentOrchestrator.executeSwarm(query, contextBuilder.toString(), domain, progressConsumer, 
               finalBlueprint -> answerChunkConsumer.accept(finalBlueprint));
            return;
        }

        // 2. DIVERSE VECTOR SEARCH
        var broadSearch = SearchRequest.builder().query(query).topK(100);
        // If multiple projects selected, filter by project_id; otherwise use domain filter
        if (!projectIds.isEmpty() && projectIds.size() > 1) {
            // Multiple projects: build OR filter for project IDs
            String filterExpr = projectIds.stream()
                .map(id -> "project_id == '" + id.toString() + "'")
                .collect(Collectors.joining(" OR "));
            broadSearch.filterExpression("(" + filterExpr + ")");
            log.info("Multi-project vector search: filtering by {} project IDs", projectIds.size());
        } else if (!projectIds.isEmpty() && projectIds.size() == 1) {
            // Single project ID
            broadSearch.filterExpression("project_id == '" + projectIds.get(0).toString() + "'");
            log.debug("Single project vector search: filtering by project_id {}", projectIds.get(0));
        } else if (domain != null && !domain.equalsIgnoreCase("General")) {
            // Fallback to domain filter if no project IDs resolved
            broadSearch.filterExpression("domain == '" + domain + "'");
        }
        List<Document> allResults = new ArrayList<>(vectorStore.similaritySearch(broadSearch.build()));

        // 3. LOGIC SCAN (Heuristic for "Business Logic")
        // Force-include some Service/Controller files to give flavor for detailed queries
        if (needsAgentSwarm || queryLower.contains("business") || queryLower.contains("usecase")) {
            allResults.addAll(vectorStore.similaritySearch(
                    SearchRequest.builder().query("Service Controller Manager Business Logic").topK(20).build()));
        }
        
        // ... (Existing Logic Scan for User Query Keywords) ...
         String domainKeyword = query.toLowerCase()
                .replaceAll(
                        "\\b(what|are|the|different|services|usecases|functionality|how|is|in|a|an|exists|to|of|related)\\b",
                        "")
                .replaceAll("[\\?\\.]", "")
                .trim();

        if (!domainKeyword.isEmpty() && !needsAgentSwarm) {
             allResults.addAll(vectorStore.similaritySearch(
                    SearchRequest.builder().query(domainKeyword + " ServiceImpl Controller").topK(30).build()));
        }

        // 4. Resolve & Filter Symbols (Existing Logic)
        List<SymbolMetadata> enrichedSymbols = new ArrayList<>();
        Set<String> processedFiles = new HashSet<>();
        
        for (Document doc : allResults) {
            String sid = (String) doc.getMetadata().get("symbol_id");
            if (sid == null) continue;
            symbolRepository.findById(UUID.fromString(sid)).ifPresent(s -> {
                if (s.getSourceFile() != null && !processedFiles.contains(s.getSourceFile().getFilePath())) {
                    enrichedSymbols.add(new SymbolMetadata(s, doc.getScore()));
                    processedFiles.add(s.getSourceFile().getFilePath());
                }
            });
        }

        // 5. Build Context from Vectors
        if (progressConsumer != null)
            progressConsumer.accept("Synthesizing " + enrichedSymbols.size() + " code vectors...");
        
        int included = 0;
        // Prioritize: Move Main/Impl/Controller to top
        enrichedSymbols.sort((a, b) -> {
             // ... (Keep existing sort logic or simplify) ...
             return Double.compare(b.score, a.score); // Simple Score sort for now, rely on Meta-Context
        });

        for (SymbolMetadata sm : enrichedSymbols) {
            String sKey = sm.symbol.getSourceFile().getStorageKey();
            if (sKey == null) continue;
            
            // Avoid duplicating if Meta-Context already grabbed it (unlikely but possible)
            if (contextBuilder.length() > MAX_CONTEXT_CHARS) break;
            
            String code = fetchSourceFromMinio(sKey, 500); // Limit vector files to 500 lines to save space for Meta
            contextBuilder.append("\n--- [Reference Component] ").append(sm.symbol.getSourceFile().getFilePath()).append(" ---\n");
            contextBuilder.append(code).append("\n");
            included++;
        }

        // 6. STREAMING REASONING
        // Note: This path only executes for simple queries (needsAgentSwarm was false)
        String prompt = String.format(
            """
                    You are a Senior Code Analyst. Answer based STRICTLY on the code provided.
                    
                    Context:
                    %s
                    
                    Question:
                    %s
                    """,
             contextBuilder.toString(), query);

        log.info("Requesting streaming synthesis...");
        chatClientBuilder.build().prompt(prompt).stream().chatResponse().toIterable().forEach(response -> {
            if (response.getResult() != null && response.getResult().getOutput() != null) {
                String text = response.getResult().getOutput().getText();
                if (text != null && !text.isEmpty()) {
                    answerChunkConsumer.accept(text);
                }
            }
        });

        if (progressConsumer != null)
            progressConsumer.accept("Analysis complete.");
    }
    
    private void injectProjectStructure(StringBuilder context, String domain, List<String> projectNames, Consumer<String> progressConsumer) {
        if (progressConsumer != null) progressConsumer.accept("Mapping Project Anatomy (Modules & Configs)...");
        
        context.append("\n=== PROJECT ANATOMY & STRUCTURE ===\n");
        
        // 1. List Modules/Projects - FILTER BY PROJECT NAMES IF MULTIPLE SELECTED
        List<com.decode.context.orchestrator.domain.Project> projects;
        if (!projectNames.isEmpty() && projectNames.size() > 1) {
            // Multiple projects selected: find by exact name match
            projects = new ArrayList<>();
            for (String projectName : projectNames) {
                Optional<com.decode.context.orchestrator.domain.Project> byName = projectRepository.findByName(projectName);
                if (byName.isPresent()) {
                    projects.add(byName.get());
                } else {
                    log.warn("Project not found: {}", projectName);
                }
            }
            if (progressConsumer != null) progressConsumer.accept("Filtering by " + projects.size() + " selected projects");
        } else if (domain != null && !domain.isEmpty() && !domain.equalsIgnoreCase("General")) {
            // Single project/domain: Try to find by project name first
            Optional<com.decode.context.orchestrator.domain.Project> byName = projectRepository.findByName(domain);
            if (byName.isPresent()) {
                // Exact match - single project
                projects = java.util.Collections.singletonList(byName.get());
                if (progressConsumer != null) progressConsumer.accept("Filtering by project: " + domain);
            } else {
                // Try filtering by domain field (groups multiple projects)
                projects = projectRepository.findByDomain(domain);
                if (projects.isEmpty()) {
                    // Fallback: partial name match (e.g., "openmrs" matches "openmrs-distro-referenceapplication")
                    projects = projectRepository.findByNameContainingIgnoreCase(domain);
                }
                if (progressConsumer != null) progressConsumer.accept("Filtering projects by domain/name: " + domain + " (" + projects.size() + " found)");
            }
        } else {
            projects = projectRepository.findAll();
        }
        
        context.append("Modules Found: ").append(projects.size()).append("\n");
        for (var p : projects) {
            context.append("- Module: ").append(p.getName())
                   .append(" [Stack: ").append(p.getTechStack() != null ? String.join(",", p.getTechStack()) : "Unknown").append("]")
                   .append(" (Path: ").append(p.getBasePath()).append(")\n");
            
            // 2. Fetch README or POM for the root of important modules
            try {
                // Heuristic: If it's a root module or major service
                if (p.getName().equals("metasfresh") || p.getName().endsWith("backend") || p.getName().endsWith("frontend")) {
                    // Try to fetch README.md
                    String readmeKey = p.getId() + "/README.md"; // Assuming ingestion stored it
                    String readme = fetchSourceFromMinio(readmeKey, 100);
                    if (!readme.contains("available")) {
                        context.append("\n--- [CONFIG] ").append(p.getName()).append("/README.md ---\n").append(readme).append("\n");
                    }
                    
                    // Try to fetch pom.xml
                    String pomKey = p.getId() + "/pom.xml";
                    String pom = fetchSourceFromMinio(pomKey, 50); // Just headers
                    if (!pom.contains("available")) {
                        context.append("\n--- [CONFIG] ").append(p.getName()).append("/pom.xml ---\n").append(pom).append("\n");
                    }
                }
            } catch (Exception e) {
                // ignore
            }
        }
        context.append("\n=====================================\n\n");
    }

    private String fetchSourceFromMinio(String storageKey, int maxLines) {
        if (storageKey == null || storageKey.isEmpty())
            return "";
        try (InputStream is = minioClient.getObject(GetObjectArgs.builder().bucket(bucket).object(storageKey).build());
                BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            return reader.lines().limit(maxLines).collect(Collectors.joining("\n"));
        } catch (Exception e) {
            return "// Source unavailable: " + storageKey;
        }
    }

    private static class SymbolMetadata {
        final Symbol symbol;
        final double score;

        SymbolMetadata(Symbol s, double sc) {
            this.symbol = s;
            this.score = sc;
        }
    }

    public String explore(String query, String domain) {
        StringBuilder sb = new StringBuilder();
        exploreStream(query, domain, null, sb::append);
        return sb.toString();
    }

    public String callLlmForRefinement(String prompt) {
        log.info("Calling LLM for blueprint refinement...");
        try {
            return chatClientBuilder.build()
                    .prompt(prompt)
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("Error calling LLM for refinement", e);
            throw new RuntimeException("Failed to refine blueprint: " + e.getMessage());
        }
    }
}
