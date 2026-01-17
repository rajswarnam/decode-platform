package com.decode.context.orchestrator.agent;

import com.decode.context.orchestrator.domain.Symbol;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AgentOrchestrator {

    private final ChatClient.Builder chatClientBuilder;
    private final VectorStore vectorStore;
    private final SymbolRepository symbolRepository;
    private final MinioClient minioClient;
    private final LexicalScoutAgent lexicalScout;
    private final QueryIntentAnalyzer queryIntentAnalyzer;

    @Value("${minio.bucket}")
    private String bucket;

    // Standard Cached ThreadPool (Java 17 compatible)
    private final ExecutorService executor = Executors.newCachedThreadPool();
    
    // Plan storage for UI transparency (sessionId -> plan data)
    private final Map<String, CurrentExecutionPlan> activePlans = new java.util.concurrent.ConcurrentHashMap<>();
    
    // Helper class to track current execution state
    @lombok.Data
    @lombok.Builder
    public static class CurrentExecutionPlan {
        private String userQuery;
        private String projectContext;
        private LexicalScoutAgent.DomainMap domainMap; // NEW
        private QueryIntentAnalyzer.QueryIntent queryIntent; // NEW
        private List<WorkerTask> tasks;
        private List<String> qaReports;
        private int currentIteration;
        private String finalResult; // NEW: Store synthesis result for retrieval
        private boolean complete; // NEW: Track completion status (changed from isComplete to avoid Lombok naming issue)
        private String errorMessage; // NEW: Store error if synthesis fails
        private String domain; // NEW: Store domain/project name for filtering
    }
    
    public CurrentExecutionPlan getCurrentPlan(String sessionId) {
        return activePlans.get(sessionId);
    }

    public String executeSwarm(String userQuery, String projectContext, String domain, Consumer<String> progressConsumer, Consumer<String> resultConsumer) {
        log.info("Starting Agent Swarm for: {}", userQuery);
        
        // Generate session ID for plan tracking
        String sessionId = UUID.randomUUID().toString();

        // PHASE 0: LEXICAL SCOUT - Domain Discovery (NEW!)
        progressConsumer.accept("🔍 Phase 0: Lexical Scout - Discovering domain vocabulary...");
        LexicalScoutAgent.DomainMap domainMap = lexicalScout.discoverDomain(projectContext, progressConsumer);
        
        // Enrich project context with domain knowledge
        String enrichedContext = projectContext + "\n\n=== DOMAIN MAP (from Lexical Scout) ===\n" + domainMap.getDomainSummary();
        
        log.info("Domain discovery complete. Found {} entities across {} modules", 
            domainMap.getTopEntities().size(), domainMap.getModules().size());

        // ANALYZE QUERY INTENT: Comprehensive vs Focused
        QueryIntentAnalyzer.QueryIntent intent = queryIntentAnalyzer.analyzeIntent(userQuery, domainMap);
        log.info("Query Intent: mode={}, topK={}, reasoning={}", intent.getMode(), intent.getRecommendedTopK(), intent.getReasoning());
        if (progressConsumer != null) {
            progressConsumer.accept("🎯 Analysis Mode: " + intent.getMode() + " (" + intent.getReasoning() + ")");
        }

        // PHASE 1: ARCHITECT PLANNING (now with domain knowledge + query intent)
        // Detect business vs technical intent first
        Intent queryIntentType = detectIntent(userQuery);
        List<WorkerTask> plan = createExecutionPlan(userQuery, enrichedContext, queryIntentType, progressConsumer);
        
        // Store initial plan with query intent for worker access
        CurrentExecutionPlan executionPlan = CurrentExecutionPlan.builder()
            .userQuery(userQuery)
            .projectContext(enrichedContext) // Store enriched context
            .domainMap(domainMap) // Store Scout findings
            .queryIntent(intent) // Store query intent for dual-mode search
            .tasks(plan) // Store worker tasks
            .qaReports(new ArrayList<>())
            .currentIteration(0)
            .domain(domain) // Store domain/project name for filtering
            .build();
        activePlans.put(sessionId, executionPlan);
        
        // Send sessionId to frontend via progress
        progressConsumer.accept("SESSION_ID:" + sessionId);
        
        // DEEP DISCOVERY MODE: 4-Pass Iteration
        // DEEP DISCOVERY MODE: 4-Pass Iteration
        int maxIterations = 4; // Initial → QA → Deep Dive → Cross-Validation
        String qaReport = "";
        
        for (int iteration = 1; iteration <= maxIterations; iteration++) {
            final int currentIter = iteration;
            executionPlan.setCurrentIteration(iteration);
            
            String iterationMode = switch(iteration) {
                case 1 -> "Initial Discovery";
                case 2 -> "Targeted Deep Dive";
                case 3 -> "Cross-Validation";
                case 4 -> "Final Evidence Synthesis";
                default -> "Analysis Pass " + iteration;
            };
            
            progressConsumer.accept("🔬 Iteration " + iteration + "/" + maxIterations + ": " + iterationMode);
            
            // EXECUTE PENDING TASKS (pass execution plan for query intent access)
            List<CompletableFuture<WorkerTask>> futures = plan.stream()
                .filter(t -> !t.getStatus().equals("COMPLETED_SATISFIED")) // Only run pending/refined tasks
                .map(task -> CompletableFuture.supplyAsync(() -> executeTaskWithType(task, currentIter, executionPlan, progressConsumer), executor))
                .collect(Collectors.toList());

            if (futures.isEmpty()) {
                progressConsumer.accept("✅ All workers satisfied. Proceeding to synthesis.");
                break;
            }

            // Wait for swarm
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            
            // PHASE 3: QA AUDIT
            qaReport = runDeepQACheck(plan, progressConsumer);
            executionPlan.getQaReports().add(qaReport);
            
            // DECISION POINT
            if (iteration < maxIterations) {
                boolean needsRefinement = refineTasksBasedOnQA(plan, qaReport, iteration, progressConsumer);
                
                // SPECIALIST SPAWNING: If critical gaps found, add new workers
                if (iteration == 2 && qaReport.contains("❌")) {
                    spawnSpecialistWorkers(plan, qaReport, progressConsumer);
                }
                
                if (!needsRefinement && plan.stream().noneMatch(t -> t.getStatus().equals("REFINING"))) {
                    progressConsumer.accept("✅ Architect: QA approved all findings. Proceeding to synthesis.");
                    break;
                }
            }
        }

        // PHASE 4: FINAL SYNTHESIS
        try {
            if (progressConsumer != null) {
                progressConsumer.accept("📝 Generating final synthesis...");
                // Send keep-alive ping during synthesis to prevent timeout
                progressConsumer.accept("💓 Keep-alive: Synthesis in progress...");
            }
            
            String synthesis = synthesizeResults(userQuery, plan, qaReport, progressConsumer);
            
            if (synthesis != null && !synthesis.isEmpty()) {
                // Store result in execution plan for retrieval if stream fails
                executionPlan.setFinalResult(synthesis);
                executionPlan.setComplete(true);
                executionPlan.setErrorMessage(null);
                
                resultConsumer.accept(synthesis);
                if (progressConsumer != null) {
                    progressConsumer.accept("✅ Synthesis complete. Analysis finished successfully.");
                    progressConsumer.accept("COMPLETE:" + sessionId); // Signal completion with sessionId
                }
            } else {
                log.warn("Synthesis returned empty result");
                String fallbackResult = buildFallbackResult(plan);
                executionPlan.setFinalResult(fallbackResult);
                executionPlan.setComplete(true);
                executionPlan.setErrorMessage("Synthesis returned empty result");
                
                if (progressConsumer != null) {
                    progressConsumer.accept("⚠️ Synthesis returned empty result. Workers completed successfully but final synthesis failed.");
                    progressConsumer.accept("COMPLETE:" + sessionId); // Signal completion
                }
                resultConsumer.accept(fallbackResult);
            }
        } catch (Exception e) {
            log.error("Error during synthesis", e);
            // Build fallback result from worker reports
            String fallbackResult = buildFallbackResult(plan);
            executionPlan.setFinalResult(fallbackResult);
            executionPlan.setComplete(true);
            executionPlan.setErrorMessage("Synthesis error: " + e.getMessage());
            
            if (progressConsumer != null) {
                progressConsumer.accept("⚠️ Synthesis error: " + e.getMessage() + ". Workers completed successfully - " + plan.size() + " tasks finished.");
                progressConsumer.accept("COMPLETE:" + sessionId); // Signal completion even on error
            }
            resultConsumer.accept(fallbackResult);
        }
        
        // Keep plan in memory for 1 hour for UI access
        // (In production, you'd want to store this in Redis or a database)
        
        return sessionId;
    }
    
    private void spawnSpecialistWorkers(List<WorkerTask> existingTasks, String qaReport, Consumer<String> progressConsumer) {
        progressConsumer.accept("🚨 Critical gaps detected. Spawning specialist workers...");
        
        // Use LLM to identify what specialists are needed
        String prompt = String.format("""
            You are the Head Architect.
            QA has flagged critical gaps:
            %s
            
            Existing Workers: %s
            
            Should we spawn additional specialist workers? If yes, output ONE line per specialist:
            Format: PERSONA|FOCUS_AREA|SPECIFIC_QUESTION
            
            If no specialists needed, output: NONE
            """, qaReport, existingTasks.stream().map(t -> t.getPersona().name()).collect(Collectors.joining(", ")));
            
        String response = blockingCall(prompt).trim();
        
        if (!response.equals("NONE") && response.contains("|")) {
            for (String line : response.split("\n")) {
                if (line.contains("|")) {
                    String[] parts = line.split("\\|", 3);
                    if (parts.length == 3) {
                        try {
                            WorkerTask specialist = WorkerTask.builder()
                                .taskId(UUID.randomUUID().toString())
                                .persona(WorkerPersona.valueOf(parts[0].trim()))
                                .focusArea(parts[1].trim())
                                .specificQuestion(parts[2].trim())
                                .status("PENDING")
                                .attemptCount(0)
                                .build();
                            existingTasks.add(specialist);
                            progressConsumer.accept("➕ Spawned: " + specialist.getPersona().getTitle() + " for " + specialist.getFocusArea());
                        } catch (IllegalArgumentException e) {
                            log.warn("Invalid specialist persona: {}", parts[0]);
                        }
                    }
                }
            }
        }
    }
    
    private boolean refineTasksBasedOnQA(List<WorkerTask> tasks, String qaReport, int iteration, Consumer<String> progressConsumer) {
        progressConsumer.accept("🔄 Architect: Reviewing QA Critique for Refinement...");
        
        // SMART SKIP: If ALL workers found 0 files, don't refine - there's no code to analyze
        boolean anyWorkerFoundCode = tasks.stream()
            .anyMatch(t -> t.getReport() != null && !t.getReport().contains("No relevant code found"));
        
        if (!anyWorkerFoundCode) {
            log.warn("All workers found 0 files. Skipping refinement - no code to analyze.");
            progressConsumer.accept("⚠️ Architect: All workers found 0 files. Skipping further iterations.");
            // Mark all as satisfied to stop the loop
            tasks.forEach(t -> t.setStatus("COMPLETED_SATISFIED"));
            return false;
        }
        
        // Architect decides if workers need to dig deeper
        // heuristic: if QA report contains "❌" or "⚠️", we refine.
        if (!qaReport.contains("❌") && !qaReport.contains("⚠️")) return false;
        
        for (WorkerTask task : tasks) {
            // Skip refinement for workers that found no code
            if (task.getReport() != null && task.getReport().contains("No relevant code found")) {
                task.setStatus("COMPLETED_SATISFIED");
                continue;
            }
            
            // Check if this specific agent was criticized in the report
            String updatePrompt = String.format("""
                You are the Head Architect (Iteration %d/4).
                Worker: %s (Focus: %s)
                Previous Report: %s
                
                QA Critique:
                %s
                
                Instruction:
                If the QA Critique highlights missing info for THIS worker, generate a NEW, CONCISE Question (one sentence).
                Focus on: %s
                If the report is fine, output "SATISFIED".
                
                Output ONLY the question or "SATISFIED", nothing else:
                """, iteration, task.getPersona().name(), task.getFocusArea(), task.getReport(), qaReport,
                iteration == 2 ? "Evidence and specific file citations" :
                iteration == 3 ? "Cross-references with other agents' findings" :
                "Final validation and completeness");
                
             String rawResponse = blockingCall(updatePrompt).trim();
             
             // Clean up LLM response - extract just the question
             String cleanedQuestion = rawResponse
                 .replaceAll("(?i)\\*\\*.*?\\*\\*:?", "") // Remove **headers**
                 .replaceAll("(?i)new question:?", "")    // Remove "New Question:"
                 .replaceAll("(?i)deeper question:?", "") // Remove "Deeper Question:"
                 .replaceAll("^[-•]\\s*", "")             // Remove bullet points
                 .replaceAll("\\n+", " ")                 // Replace newlines with spaces
                 .trim();
             
             if (!cleanedQuestion.contains("SATISFIED") && cleanedQuestion.length() > 10) {
                 task.setStatus("REFINING");
                 task.setSpecificQuestion(cleanedQuestion);
                 task.setAttemptCount(task.getAttemptCount() + 1);
                 log.info("Refined question for {}: {}", task.getPersona(), cleanedQuestion);
                 progressConsumer.accept("🔁 Re-assigning " + task.getPersona().getTitle() + ": " + cleanedQuestion.substring(0, Math.min(80, cleanedQuestion.length())) + "...");
             } else {
                 task.setStatus("COMPLETED_SATISFIED");
             }
        }
        return tasks.stream().anyMatch(t -> t.getStatus().equals("REFINING"));
    }

    // --- ROBUST LLM CALLER (Stream Aggregation) ---
    private String blockingCall(String promptContext) {
        StringBuilder sb = new StringBuilder();
        try {
            // We use stream() to handle both 'text/event-stream' and standard responses robustly
            chatClientBuilder.build()
                .prompt(promptContext)
                .stream()
                .chatResponse()
                .toIterable()
                .forEach(response -> {
                    if (response.getResult() != null && response.getResult().getOutput() != null) {
                        String content = response.getResult().getOutput().getText(); 
                        if (content != null) {
                            sb.append(content);
                        }
                    }
                });
        } catch (Exception e) {
            log.error("LLM Call Failed", e);
            return "Error generating response: " + e.getMessage();
        }
        return sb.toString();
    }

    private List<WorkerTask> createExecutionPlan(String query, String context, Intent intentType, Consumer<String> progressConsumer) {
        if (progressConsumer != null) progressConsumer.accept("🧠 Head Architect: Devising execution plan...");
        
        String intentGuidance;
        String exampleTasks;
        
        if (intentType == Intent.BUSINESS) {
            intentGuidance = """
            **BUSINESS QUERY DETECTED**: Focus EXCLUSIVELY on discovering business domains, use cases, and capabilities.
            
            **YOUR MISSION**: Discover all major business domains this system supports by analyzing the codebase structure and business entities.
            
            **HOW TO DISCOVER DOMAINS** (domain-agnostic approach):
            1. Look for package/module names, table names, service names, class names that suggest business concepts
            2. Identify business entity classes/tables (e.g., entities like Order, Customer, Product, Invoice, Transaction, etc.)
            3. Look for workflow/process names that indicate business capabilities
            4. Examine domain-specific vocabulary in the codebase
            5. Focus on WHAT business problems the system solves, not how it's implemented
            6. Create tasks to DISCOVER and DOCUMENT whatever business domains exist in THIS codebase
            
            **DISCOVERY STRATEGY**:
            - Let the codebase tell you what domains exist - don't assume any particular industry
            - Group related business entities together to infer domains
            - Extract domain names from the actual code structure (package names, module names, entity names)
            - The system might be an ERP, CRM, e-commerce, healthcare, finance, education, or any other domain - discover it organically
            
            **CRITICAL - DO NOT CREATE TASKS ABOUT**:
            - Type mismatches, JSON schemas, API contracts, frontend/backend alignment
            - Error handling, asynchronous processing, data consistency (unless from business perspective)
            - Technical implementation details
            - These are TECHNICAL concerns, NOT business domain discovery
            
            **CREATE TASKS ABOUT**:
            - Discovering whatever business domains exist in the codebase (discover organically, don't assume)
            - Understanding business workflows and use cases found in the code
            - Mapping business entities discovered in the code
            - Identifying user journeys from a business perspective
            """;
            exampleTasks = """
            CORRECT Example tasks (domain-agnostic - adapt to what you find):
            LOGIC_EXTRACTOR|primary-domain|What is the primary business domain this system addresses? What are the main business use cases?
            LOGIC_EXTRACTOR|business-modules|What distinct business modules or domains exist in this system based on the code structure?
            DATABASE_SQL|business-entities|What are the main business entities and their relationships? (Analyze tables/classes to infer business domains)
            LOGIC_EXTRACTOR|business-workflows|What key business workflows and processes does this system support?
            LOGIC_EXTRACTOR|user-capabilities|What business capabilities does this system provide to end users?
            
            WRONG Example tasks (too technical - avoid these):
            LOGIC_EXTRACTOR|Aligning Frontend/Backend Interfaces|What technical changes are needed...
            LOGIC_EXTRACTOR|Defining API Endpoints|What API endpoints are needed...
            LOGIC_EXTRACTOR|Error Handling Improvements|What mechanisms can be implemented...
            
            Note: Don't assume specific domains like "Sales", "HR", etc. Discover what domains actually exist in the codebase.
            """;
        } else if (intentType == Intent.TECHNICAL) {
            intentGuidance = """
            **TECHNICAL QUERY DETECTED**: Focus on implementation details, code quality, type safety, and technical architecture.
            - Investigate technical implementations, APIs, and data structures
            - Check for type mismatches, schema drift, and boundary contracts
            - Analyze code quality, performance, and reliability
            """;
            exampleTasks = """
            Example tasks for TECHNICAL queries:
            BACKEND_JAVA|procurement-backend|Analyze the logic for Purchase Order creation.
            FRONTEND_REACT|frontend-webui|Identify how users trigger the order process.
            DATABASE_SQL|schema-design|Review database schema and relationships.
            """;
        } else {
            intentGuidance = """
            **GENERAL QUERY**: Balance business context with technical details as needed.
            """;
            exampleTasks = """
            BACKEND_JAVA|procurement-backend|Analyze the logic for Purchase Order creation.
            FRONTEND_REACT|frontend-webui|Identify how users trigger the order process.
            """;
        }
        
        String prompt = String.format("""
            You are the Head Architect.
            User Goal: "%s"
            Project Context:
            %s
            
            %s
            
            CRITICAL INSTRUCTION:
            1. Your Primary Focus is the USER GOAL.
            2. Do NOT be distracted by specific bugs or logs in the context unless the User Goal asks to fix them.
            3. For BUSINESS queries: IGNORE all technical details in the context. Focus ONLY on discovering business domains and use cases.
            4. If you see technical concerns (type mismatches, API alignment, error handling) in the context, SKIP THEM for business queries.
            5. For BUSINESS queries, create tasks to DISCOVER business domains (Sales, HR, Marketing, Finance, etc.), not to fix technical issues.
            
            Create a Parallel Execution Plan. For BUSINESS queries, identify 5-8 distinct business domains to explore.
            Assign the best Worker Persona (BACKEND_JAVA, FRONTEND_REACT, DATABASE_SQL, LEGACY_COBOL, LOGIC_EXTRACTOR).
            For BUSINESS queries, prioritize LOGIC_EXTRACTOR and DATABASE_SQL to discover business domains.
            
            **VALIDATION**: Before creating a task, ask yourself: "Does this task help discover WHAT business domains the system supports, or does it analyze HOW the system is implemented technically?" Only create the former type of tasks.
            
            Format: ONE LINE per task.
            Format pattern: PERSONA|FOCUS_AREA|SPECIFIC_QUESTION
            
            %s
            """, query, context, intentGuidance, exampleTasks);

        String response = blockingCall(prompt);
        
        List<WorkerTask> tasks = new ArrayList<>();
        for (String line : response.split("\n")) {
            // Strip leading numbers (e.g., "1. BACKEND_JAVA|..." -> "BACKEND_JAVA|...")
            String cleanedLine = line.replaceFirst("^\\d+\\.\\s*", "").trim();
            
            if (cleanedLine.contains("|")) {
                String[] parts = cleanedLine.split("\\|", 3);
                if (parts.length == 3) {
                    try {
                        tasks.add(WorkerTask.builder()
                            .taskId(UUID.randomUUID().toString())
                            .persona(WorkerPersona.valueOf(parts[0].trim()))
                            .focusArea(parts[1].trim())
                            .specificQuestion(parts[2].trim())
                            .status("PENDING")
                            .attemptCount(0)
                            .build());
                    } catch (IllegalArgumentException e) {
                        log.warn("Invalid persona in plan: {}", parts[0]);
                    }
                }
            }
        }
        
        if (progressConsumer != null) progressConsumer.accept("📋 Plan Created: " + tasks.size() + " parallel work streams defined.");
        return tasks;
    }

    private WorkerTask executeTaskWithType(WorkerTask task, int iteration, CurrentExecutionPlan executionPlan, Consumer<String> progressConsumer) {
        task.setStatus("RUNNING");
        String mode = (iteration == 1) ? "Initial Scan" : "Deep Dive / Refinement";
        if (progressConsumer != null) progressConsumer.accept("🚀 Dispatching " + task.getPersona().getTitle() + " (" + mode + ") to analyse: " + task.getFocusArea());

        try {
            // 1. Context Retrieval (RAG) - Pass query intent for dual-mode search and domain for filtering
            QueryIntentAnalyzer.QueryIntent intent = executionPlan.getQueryIntent();
            String context = retrieveContext(task, intent, executionPlan.getDomainMap(), executionPlan.getDomain());
            
            if (context.isEmpty()) {
                task.setReport("No relevant code found query (" + task.getFocusArea() + ")");
                task.setStatus("COMPLETED_EMPTY");
                return task;
            }

            // 2. Analysis with STRICT EVIDENCE REQUIREMENT
            String prompt = String.format("""
                You are a %s.
                Role Description: %s
                
                Iteration: %d (%s)
                Your Task: %s
                Focus Area: %s
                
                Code Context:
                %s
                
                **CRITICAL REQUIREMENT - EVIDENCE-BASED REPORTING**:
                For EVERY claim you make, you MUST cite the exact file and provide a code snippet.
                
                Format your findings as:
                ### Finding: [Brief Title]
                **Evidence**: `filename.ext` (lines X-Y)
                ```
                [actual code snippet]
                ```
                **Analysis**: [What this code does and why it matters]
                
                Example:
                ### Finding: Order Service has synchronous payment dependency
                **Evidence**: `OrderService.java` (lines 45-52)
                ```java
                public Order createOrder(OrderRequest req) {
                    Payment p = paymentGateway.processPayment(req); // BLOCKING CALL
                    return orderRepo.save(new Order(req, p));
                }
                ```
                **Analysis**: This blocking call means if PaymentGateway is slow/down, order creation hangs.
                
                If you cannot find code evidence for something, explicitly state "NO EVIDENCE FOUND".
                Do NOT make assumptions or inferences without code backing.
                """, 
                task.getPersona().name(), 
                task.getPersona().getDescription(),
                iteration, mode,
                task.getSpecificQuestion(),
                task.getFocusArea(),
                context);

            String report = blockingCall(prompt);
            
            // Append finding to previous report if refining
            if (iteration > 1 && task.getReport() != null) {
                task.setReport(task.getReport() + "\n\n### Refinement (Iter " + iteration + "):\n" + report);
            } else {
                task.setReport(report);
            }
            
            // STRICT VALIDATION: Report must contain file citations
            if (!report.contains("**Evidence**:") && !report.contains("NO EVIDENCE FOUND")) {
                task.setValidationErrors("CRITICAL: Report lacks code evidence citations");
                task.setStatus("FAILED_VALIDATION");
            } else {
                task.setStatus("COMPLETED");
            }

            if (progressConsumer != null) progressConsumer.accept("✅ " + task.getPersona().getTitle() + " finished task.");

        } catch (Exception e) {
            log.error("Task failed", e);
            task.setStatus("FAILED");
            task.setReport("Error: " + e.getMessage());
        }
        return task;
    }

    private String retrieveContext(WorkerTask task, QueryIntentAnalyzer.QueryIntent intent, LexicalScoutAgent.DomainMap domainMap, String domain) {
        // DUAL-MODE SEARCH: Comprehensive vs Focused
        List<Document> docs = new ArrayList<>();
        
        if (intent == null) {
            // Fallback: Default to focused mode
            intent = QueryIntentAnalyzer.QueryIntent.builder()
                .mode(QueryIntentAnalyzer.QueryIntent.Mode.FOCUSED)
                .recommendedTopK(50)
                .useModuleSampling(false)
                .useStratifiedSearch(false)
                .build();
        }
        
        // Extract key business terms from focus area
        String focusTerms = task.getFocusArea()
            .replaceAll("-", " ")  
            .replaceAll("webui|frontend|backend|database|service", "") 
            .trim();
        String searchQuery = focusTerms + " " + task.getSpecificQuestion();
        
        log.info("Worker {} searching for: {} (mode: {}, topK: {})", 
            task.getPersona(), searchQuery, intent.getMode(), intent.getRecommendedTopK());
        
        if (intent.getMode() == QueryIntentAnalyzer.QueryIntent.Mode.COMPREHENSIVE) {
            // COMPREHENSIVE MODE: Broad coverage with module sampling
            docs = retrieveContextComprehensive(task, searchQuery, intent, domainMap, domain);
        } else if (intent.getMode() == QueryIntentAnalyzer.QueryIntent.Mode.HYBRID) {
            // HYBRID MODE: Balanced approach
            docs = retrieveContextHybrid(task, searchQuery, intent, domainMap, domain);
        } else {
            // FOCUSED MODE: Targeted semantic search (original behavior)
            docs = retrieveContextFocused(task, searchQuery, intent, domain);
        }
        
        log.info("Vector search returned {} documents for worker {} (mode: {})", 
            docs.size(), task.getPersona(), intent.getMode());
        
        // Diagnostic: If 0 results, check if vector store has any documents at all
        if (docs.isEmpty()) {
            log.warn("⚠️ No documents found for query: '{}' with domain: '{}'. Checking if vector store has any documents...", searchQuery, domain);
            var testSearch = SearchRequest.builder().query("service").topK(10).build();
            List<Document> testResults = vectorStore.similaritySearch(testSearch);
            log.warn("⚠️ Test search for 'service' returned {} documents. Vector store may be empty or domain filter too strict.", testResults.size());
        }
        
        StringBuilder sb = new StringBuilder();
        Set<String> processedFiles = new HashSet<>();
        java.util.concurrent.atomic.AtomicInteger symbolsFound = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger symbolsWithSourceFile = new java.util.concurrent.atomic.AtomicInteger(0);
        
        for (Document doc : docs) {
            String sid = (String) doc.getMetadata().get("symbol_id");
            if (sid != null) {
                symbolRepository.findById(UUID.fromString(sid)).ifPresentOrElse(s -> {
                    symbolsFound.incrementAndGet();
                    if (s.getSourceFile() != null && !processedFiles.contains(s.getSourceFile().getFilePath())) {
                        symbolsWithSourceFile.incrementAndGet();
                        sb.append("\n--- File: ").append(s.getSourceFile().getFilePath()).append(" ---\n");
                        String content = fetchSource(s.getSourceFile().getStorageKey());
                        sb.append(content);
                        processedFiles.add(s.getSourceFile().getFilePath());
                        log.debug("Added file: {} (size: {} bytes)", s.getSourceFile().getFilePath(), content.length());
                    } else if (s.getSourceFile() == null) {
                        log.warn("Symbol {} has no sourceFile attached", sid);
                    }
                }, () -> {
                    log.warn("Symbol {} not found in repository", sid);
                });
            } else {
                log.warn("Document has no symbol_id in metadata: {}", doc.getMetadata());
            }
        }
        
        log.info("Retrieved {} unique files for worker {} (symbols found: {}, with source file: {})", 
            processedFiles.size(), task.getPersona(), symbolsFound.get(), symbolsWithSourceFile.get());
        return sb.toString();
    }
    
    /**
     * FOCUSED MODE: Targeted semantic search (original behavior)
     * Uses single semantic search with standard topK
     */
    private List<Document> retrieveContextFocused(WorkerTask task, String searchQuery, QueryIntentAnalyzer.QueryIntent intent, String domain) {
        var builder = SearchRequest.builder()
            .query(searchQuery)
            .topK(intent.getRecommendedTopK()); // Usually 50
        
        // Filter by domain/project if specified
        List<Document> results;
        if (domain != null && !domain.isEmpty() && !domain.equalsIgnoreCase("General")) {
            builder.filterExpression("domain == '" + domain + "'");
            results = vectorStore.similaritySearch(builder.build());
            
            // If filter returns 0 results, try without filter (domain metadata might not exist)
            if (results.isEmpty()) {
                log.warn("Domain filter '{}' returned 0 results, retrying without filter", domain);
                var unfilteredBuilder = SearchRequest.builder()
                    .query(searchQuery)
                    .topK(intent.getRecommendedTopK());
                results = vectorStore.similaritySearch(unfilteredBuilder.build());
                
                // Filter results by checking symbol's source file project association
                if (!results.isEmpty()) {
                    log.info("Found {} documents without filter, will filter by project association", results.size());
                    results = filterByProjectAssociation(results, domain);
                }
            }
        } else {
            results = vectorStore.similaritySearch(builder.build());
        }
        
        return results;
    }
    
    /**
     * Fallback filtering: Filter documents by checking if their symbols belong to the specified project
     */
    private List<Document> filterByProjectAssociation(List<Document> docs, String domainOrProject) {
        List<Document> filtered = new ArrayList<>();
        
        for (Document doc : docs) {
            String sid = (String) doc.getMetadata().get("symbol_id");
            if (sid != null) {
                symbolRepository.findById(UUID.fromString(sid)).ifPresent(symbol -> {
                    if (symbol.getSourceFile() != null && symbol.getSourceFile().getProject() != null) {
                        String projectName = symbol.getSourceFile().getProject().getName();
                        String projectDomain = symbol.getSourceFile().getProject().getDomain();
                        
                        // Match if project name or domain matches
                        if (projectName.equalsIgnoreCase(domainOrProject) || 
                            (projectDomain != null && projectDomain.equalsIgnoreCase(domainOrProject)) ||
                            (projectName.toLowerCase().contains(domainOrProject.toLowerCase()))) {
                            filtered.add(doc);
                        }
                    }
                });
            }
        }
        
        log.info("Filtered {} documents to {} matching project '{}'", docs.size(), filtered.size(), domainOrProject);
        return filtered;
    }
    
    /**
     * HYBRID MODE: Balanced approach
     * Combines semantic search with light module sampling
     */
    private List<Document> retrieveContextHybrid(WorkerTask task, String searchQuery, 
                                                 QueryIntentAnalyzer.QueryIntent intent, 
                                                 LexicalScoutAgent.DomainMap domainMap, String domain) {
        Set<Document> combinedDocs = new LinkedHashSet<>();
        
        // 1. Primary semantic search
        var primaryBuilder = SearchRequest.builder()
            .query(searchQuery)
            .topK(intent.getRecommendedTopK() / 2); // 50 for hybrid
        List<Document> primaryResults;
        if (domain != null && !domain.isEmpty() && !domain.equalsIgnoreCase("General")) {
            primaryBuilder.filterExpression("domain == '" + domain + "'");
            primaryResults = vectorStore.similaritySearch(primaryBuilder.build());
            if (primaryResults.isEmpty()) {
                // Fallback: retry without filter
                var unfiltered = SearchRequest.builder().query(searchQuery).topK(intent.getRecommendedTopK() / 2).build();
                primaryResults = filterByProjectAssociation(vectorStore.similaritySearch(unfiltered), domain);
            }
        } else {
            primaryResults = vectorStore.similaritySearch(primaryBuilder.build());
        }
        combinedDocs.addAll(primaryResults);
        
        // 2. Light module sampling (1 file per top 5 modules)
        if (intent.isUseModuleSampling() && domainMap != null && !domainMap.getModules().isEmpty()) {
            List<LexicalScoutAgent.ModuleCluster> topModules = domainMap.getModules().stream()
                .limit(5) // Top 5 modules
                .collect(Collectors.toList());
            
            for (LexicalScoutAgent.ModuleCluster module : topModules) {
                String moduleQuery = module.getModuleName() + " " + searchQuery;
                var moduleBuilder = SearchRequest.builder()
                    .query(moduleQuery)
                    .topK(10); // 1-2 files per module
                List<Document> moduleResults;
                if (domain != null && !domain.isEmpty() && !domain.equalsIgnoreCase("General")) {
                    moduleBuilder.filterExpression("domain == '" + domain + "'");
                    moduleResults = vectorStore.similaritySearch(moduleBuilder.build());
                    if (moduleResults.isEmpty()) {
                        var unfiltered = SearchRequest.builder().query(moduleQuery).topK(10).build();
                        moduleResults = filterByProjectAssociation(vectorStore.similaritySearch(unfiltered), domain);
                    }
                } else {
                    moduleResults = vectorStore.similaritySearch(moduleBuilder.build());
                }
                combinedDocs.addAll(moduleResults);
            }
        }
        
        return new ArrayList<>(combinedDocs);
    }
    
    /**
     * COMPREHENSIVE MODE: Broad coverage with module sampling + stratified search
     * Uses multiple search strategies to ensure comprehensive coverage
     */
    private List<Document> retrieveContextComprehensive(WorkerTask task, String searchQuery,
                                                       QueryIntentAnalyzer.QueryIntent intent,
                                                       LexicalScoutAgent.DomainMap domainMap, String domain) {
        Set<Document> combinedDocs = new LinkedHashSet<>();
        
        // 1. Primary semantic search (40% of results)
        var primaryBuilder = SearchRequest.builder()
            .query(searchQuery)
            .topK((int) (intent.getRecommendedTopK() * 0.4)); // ~80 documents
        List<Document> primaryResults;
        if (domain != null && !domain.isEmpty() && !domain.equalsIgnoreCase("General")) {
            primaryBuilder.filterExpression("domain == '" + domain + "'");
            primaryResults = vectorStore.similaritySearch(primaryBuilder.build());
            if (primaryResults.isEmpty()) {
                // Fallback: retry without filter
                log.warn("Domain filter '{}' returned 0 results in comprehensive mode, retrying without filter", domain);
                var unfiltered = SearchRequest.builder().query(searchQuery).topK((int) (intent.getRecommendedTopK() * 0.4)).build();
                primaryResults = filterByProjectAssociation(vectorStore.similaritySearch(unfiltered), domain);
            }
        } else {
            primaryResults = vectorStore.similaritySearch(primaryBuilder.build());
        }
        combinedDocs.addAll(primaryResults);
        
        // 2. Stratified search: Multiple business domain queries (30% of results)
        if (intent.isUseStratifiedSearch() && domainMap != null) {
            List<String> businessDomains = domainMap.getTopEntities().stream()
                .limit(5) // Top 5 entities
                .map(LexicalScoutAgent.BusinessEntity::getName)
                .collect(Collectors.toList());
            
            for (String businessDomain : businessDomains) {
                String domainQuery = businessDomain + " " + searchQuery;
                var domainBuilder = SearchRequest.builder()
                    .query(domainQuery)
                    .topK(intent.getRecommendedTopK() / 10); // ~20 per domain
                List<Document> domainResults;
                if (domain != null && !domain.isEmpty() && !domain.equalsIgnoreCase("General")) {
                    domainBuilder.filterExpression("domain == '" + domain + "'");
                    domainResults = vectorStore.similaritySearch(domainBuilder.build());
                    if (domainResults.isEmpty()) {
                        var unfiltered = SearchRequest.builder().query(domainQuery).topK(intent.getRecommendedTopK() / 10).build();
                        domainResults = filterByProjectAssociation(vectorStore.similaritySearch(unfiltered), domain);
                    }
                } else {
                    domainResults = vectorStore.similaritySearch(domainBuilder.build());
                }
                combinedDocs.addAll(domainResults);
            }
        }
        
        // 3. Module sampling: Ensure coverage from each major module (30% of results)
        if (intent.isUseModuleSampling() && domainMap != null && !domainMap.getModules().isEmpty()) {
            List<LexicalScoutAgent.ModuleCluster> topModules = domainMap.getModules().stream()
                .limit(10) // Top 10 modules
                .collect(Collectors.toList());
            
            int perModule = intent.getRecommendedTopK() / 20; // ~10 per module
            for (LexicalScoutAgent.ModuleCluster module : topModules) {
                String moduleQuery = module.getModuleName() + " " + searchQuery;
                var moduleBuilder = SearchRequest.builder()
                    .query(moduleQuery)
                    .topK(Math.max(perModule, 5)); // At least 5, up to perModule
                List<Document> moduleResults;
                if (domain != null && !domain.isEmpty() && !domain.equalsIgnoreCase("General")) {
                    moduleBuilder.filterExpression("domain == '" + domain + "'");
                    moduleResults = vectorStore.similaritySearch(moduleBuilder.build());
                    if (moduleResults.isEmpty()) {
                        var unfiltered = SearchRequest.builder().query(moduleQuery).topK(Math.max(perModule, 5)).build();
                        moduleResults = filterByProjectAssociation(vectorStore.similaritySearch(unfiltered), domain);
                    }
                } else {
                    moduleResults = vectorStore.similaritySearch(moduleBuilder.build());
                }
                combinedDocs.addAll(moduleResults);
            }
        }
        
        // 4. Fallback: Generic search if not enough results
        if (combinedDocs.size() < intent.getRecommendedTopK() * 0.5) {
            List<String> genericQueries = List.of(
                "service controller repository",
                "business logic implementation",
                "domain model entity"
            );
            
            for (String genericQuery : genericQueries) {
                var fallbackBuilder = SearchRequest.builder()
                    .query(genericQuery + " " + searchQuery)
                    .topK(20);
                List<Document> fallbackResults;
                if (domain != null && !domain.isEmpty() && !domain.equalsIgnoreCase("General")) {
                    fallbackBuilder.filterExpression("domain == '" + domain + "'");
                    fallbackResults = vectorStore.similaritySearch(fallbackBuilder.build());
                    if (fallbackResults.isEmpty()) {
                        var unfiltered = SearchRequest.builder().query(genericQuery + " " + searchQuery).topK(20).build();
                        fallbackResults = filterByProjectAssociation(vectorStore.similaritySearch(unfiltered), domain);
                    }
                } else {
                    fallbackResults = vectorStore.similaritySearch(fallbackBuilder.build());
                }
                combinedDocs.addAll(fallbackResults);
                if (combinedDocs.size() >= intent.getRecommendedTopK()) break;
            }
        }
        
        // Limit to recommended topK (remove duplicates handled by Set)
        return combinedDocs.stream()
            .limit(intent.getRecommendedTopK())
            .collect(Collectors.toList());
    }
    
    private String fetchSource(String key) {
        try (InputStream is = minioClient.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            return reader.lines().limit(300).collect(Collectors.joining("\n"));
        } catch (Exception e) {
            return "// Source unavailable";
        }
    }

    private String runDeepQACheck(List<WorkerTask> results, Consumer<String> progressConsumer) {
        if (progressConsumer != null) progressConsumer.accept("🕵🏻 QA Agent: Auditing Type Safety & Schema Drift...");
        
        StringBuilder sourceMaterial = new StringBuilder();
        for (WorkerTask t : results) {
            sourceMaterial.append("\n=== SOURCE: ").append(t.getPersona().name()).append(" ===\n")
                          .append(t.getReport()).append("\n");
        }
        
        String prompt = String.format("""
            You are the Lead SRE and QA Architect.
            task: Perform a "Schema Drift" audit between Backend and Frontend agents.
            
            Source Reports:
            %s
            
            **CRITICAL INSTRUCTIONS**:
            1. **Detect Type Mismatches**: Does the Backend say "Returns Integer" but Frontend says "Expects JSON"?
            2. **Identify SRE Risks**: Are there synchronous dependencies (e.g. OrderService calls PaymentService blocking)?
            3. **Verify Boundary Contracts**: Do REST paths match REDUX Action payloads?
            
            Output a specialized "QA Audit Log" identifying these specific risks. 
            Use emojis: ❌ for Drift, ⚠️ for Weakness, ✅ for Match.
            """, sourceMaterial.toString());
            
        return blockingCall(prompt);
    }

    private String synthesizeResults(String query, List<WorkerTask> results, String qaReport, Consumer<String> progressConsumer) {
        if (progressConsumer != null) {
            progressConsumer.accept("🧠 Chief Architect: Generating Final Strategic Document...");
            // Send keep-alive ping to prevent timeout during synthesis
            progressConsumer.accept("💓 Keep-alive: Synthesis in progress...");
        }
        
        // Start keep-alive ping thread during synthesis
        Thread keepAliveThread = null;
        if (progressConsumer != null) {
            keepAliveThread = new Thread(() -> {
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        Thread.sleep(30000); // Every 30 seconds
                        progressConsumer.accept("💓 Keep-alive: Still synthesizing...");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            keepAliveThread.setDaemon(true);
            keepAliveThread.start();
        }
        
        StringBuilder inputs = new StringBuilder();
        for (WorkerTask t : results) {
            inputs.append("\n### Source: ").append(t.getPersona().getTitle())
                  .append("\n**Findings**:\n").append(t.getReport()).append("\n");
        }
        
        // 1. Semantic Intent Detection (LLM-based)
        Intent intent = detectIntent(query);
        if (progressConsumer != null) progressConsumer.accept("INTENT_DETECTED:" + intent.name());

        boolean isBusinessRequest = (intent == Intent.BUSINESS);
        
        String promptTemplate;
        if (isBusinessRequest) {
            promptTemplate = """
            You are a Functional Solution Architect.
            The user wants a Business/Functional Analysis of: "%s"
            
            Synthesize the Code Parser Findings into a "Business Use Cases Overview".
            
            **CRITICAL**: This is a BUSINESS query. Focus on WHAT the system does, not HOW it's implemented.
            The user wants to understand the business domains, use cases, and capabilities of the system.
            
            Structure your response as follows:
            
            1. **System Overview**: Start with a high-level description of what type of system this is based on the codebase analysis (e.g., "This is an ERP system" or "This is a healthcare management system" or "This is a financial trading platform"). 
               Let the codebase tell you what it is - don't assume.
            
            2. **Business Domains/Modules**: Identify major business modules/domains discovered in the codebase.
               For each domain found, describe:
               - What business capabilities it provides
               - What problems it solves
               - Key business entities it manages
               - Don't assume any particular domains - discover them from the code
            
            3. **Key Business Use Cases**: List the main business workflows and use cases the system supports, based on what you found in the code.
               Describe the actual use cases discovered, such as:
               - Core business processes supported
               - Main workflows implemented
               - Key business operations enabled
            
            4. **Business Entities**: Map major business concepts discovered to their technical representations (if helpful, but keep it business-focused).
               Use the actual entities found in the codebase.
            
            5. **User Workflows**: Describe key user journeys from a business perspective based on workflows found in the code.
            
            **DO NOT**:
            - Focus on technical implementation details (JSON schemas, type mismatches, API contracts)
            - Include low-level code analysis unless it directly impacts business functionality
            - Emphasize SRE concerns, performance metrics, or technical debt
            
            **DO**:
            - Explain the system in business terms
            - Focus on capabilities and value to end users
            - Use business terminology, not technical jargon
            
            **Worker Reports**:
            %s
            
            **QA Findings** (only include if relevant to business functionality):
            %s
            """;
        } else {
            promptTemplate = """
            You are a Site Reliability Engineer (SRE).
            The user wants a Technical/SRE Analysis of: "%s"
            
            Synthesize the observations into an "SRE Master Blueprint".
            
            Structure:
            1. **Critical Paths**: Identify blocking calls and performance bottlenecks.
            2. **Stability Risks**: Highlight messy code, lack of tests, or risky async patterns.
            3. **Tier Compliance**: Check for REST contract violations or schema drift.
            4. **Resilience**: Suggest improvements for retry logic and transaction boundaries.
            
            Be critical. Focus on Stability, Performance, and Code Quality.
            
            **Worker Reports**:
            %s
            
            **Risks**:
            %s
            """;
        }
        
        String prompt = String.format(promptTemplate, query, inputs.toString(), qaReport);

        String result;
        try {
            result = blockingCall(prompt);
        } finally {
            // Stop keep-alive thread when synthesis completes
            if (keepAliveThread != null && keepAliveThread.isAlive()) {
                keepAliveThread.interrupt();
            }
        }
        
        return result;
    }
    
    /**
     * Build fallback result from worker reports when synthesis fails
     */
    private String buildFallbackResult(List<WorkerTask> plan) {
        StringBuilder result = new StringBuilder("# Analysis Results\n\n");
        result.append("**Note**: Final synthesis encountered an error, but all workers completed successfully.\n\n");
        result.append("## Worker Findings\n\n");
        
        for (WorkerTask task : plan) {
            if (task.getReport() != null && !task.getReport().isEmpty()) {
                result.append("### ").append(task.getPersona().getTitle()).append(" - ").append(task.getFocusArea()).append("\n\n");
                result.append(task.getReport()).append("\n\n");
            }
        }
        
        return result.toString();
    }

    private enum Intent { BUSINESS, TECHNICAL, GENERAL }

    private Intent detectIntent(String query) {
        // 0. Manual Override for Speed & Certainty
        String q = query.toLowerCase();
        if (q.matches(".*(overview|business|functional|use.?case|blueprint|requirements|brd).*")) {
            return Intent.BUSINESS;
        }

        String sys = """
            Classify the User Query into one of these intents:
            - BUSINESS: Questions about Features, Workflows, 'How does it work', Overviews, Architecture, Domain Logic.
            - TECHNICAL: Questions about Bugs, Errors, Logs, Performance, SRE, Refactoring specific lines.
            - GENERAL: Greetings or indistinct queries.
            
            Return ONLY the Word: BUSINESS, TECHNICAL, or GENERAL.
            """;
            
        try {
            String response = chatClientBuilder.build().prompt()
                .system(sys)
                .user(query)
                .call()
                .content()
                .trim()
                .toUpperCase();
                
            if (response.contains("BUSINESS")) return Intent.BUSINESS;
            if (response.contains("TECHNICAL")) return Intent.TECHNICAL;
            return Intent.GENERAL;
        } catch (Exception e) {
            System.err.println("Intent Detection Failed, defaulting to TECHNICAL: " + e.getMessage());
            return Intent.TECHNICAL;
        }
    }
}
