package com.decode.context.orchestrator.agent;

import com.decode.context.orchestrator.domain.Symbol;
import com.decode.context.orchestrator.repository.SymbolRepository;
import com.decode.context.orchestrator.repository.SourceFileRepository;
import com.decode.context.orchestrator.repository.ProjectRepository;
import com.decode.context.orchestrator.domain.Project;
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
    private final SourceFileRepository sourceFileRepository;
    private final ProjectRepository projectRepository;
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
        LexicalScoutAgent.DomainMap domainMap = lexicalScout.discoverDomain(projectContext, domain, progressConsumer);
        
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
        
        // DEEP DISCOVERY MODE: Dynamic iterations based on coverage
        // Start with 4 iterations, but expand if coverage is critically low
        int maxIterations = 4; // Initial → QA → Deep Dive → Cross-Validation
        int maxAllowedIterations = 8; // Hard cap to prevent infinite loops
        String qaReport = "";
        boolean hasCriticalLowCoverage = false;
        
        for (int iteration = 1; iteration <= maxIterations; iteration++) {
            final int currentIter = iteration;
            executionPlan.setCurrentIteration(iteration);
            
            String iterationMode = switch(iteration) {
                case 1 -> "Initial Discovery";
                case 2 -> "Targeted Deep Dive";
                case 3 -> "Cross-Validation";
                case 4 -> "Final Evidence Synthesis";
                default -> "Extended Analysis Pass " + iteration;
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
            qaReport = runDeepQACheck(plan, executionPlan, progressConsumer);
            executionPlan.getQaReports().add(qaReport);
            
            // DECISION POINT
            if (iteration < maxIterations) {
                boolean needsRefinement = refineTasksBasedOnQA(plan, qaReport, iteration, executionPlan, progressConsumer, () -> hasCriticalLowCoverage = true);
                
                // SPECIALIST SPAWNING: If critical gaps found, add new workers
                if (iteration == 2 && qaReport.contains("❌")) {
                    spawnSpecialistWorkers(plan, qaReport, progressConsumer);
                }
                
                // DYNAMIC ITERATION EXPANSION: If coverage is critically low, extend iterations
                if (hasCriticalLowCoverage && maxIterations < maxAllowedIterations) {
                    maxIterations = Math.min(maxIterations + 2, maxAllowedIterations); // Add 2 more iterations
                    log.warn("⚠️ CRITICAL: Low coverage detected. Extending iterations from {} to {}", iteration + 1, maxIterations);
                    progressConsumer.accept("⚠️ Architect: Low coverage detected. Extending analysis to " + maxIterations + " iterations...");
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
            
            **CRITICAL RULES**:
            1. DO NOT create focus areas about "COVERAGE", "MODULE_DIVERSITY", "EVIDENCE_QUALITY", "SRE_RISKS", or "CONTRACT_CLARITY"
            2. DO NOT ask meta-questions about coverage or analysis quality
            3. Focus areas should be ACTUAL business domains or technical areas (e.g., "patient-management", "medication-dispense", "data-integrity")
            4. Questions should ask about SPECIFIC business functionality, workflows, or technical implementations
            5. Examples of GOOD focus areas: "patient-registration", "medication-orders", "encounter-management"
            6. Examples of BAD focus areas: "COVERAGE", "MODULE_DIVERSITY", "evidence-quality-improvement"
            
            If no specialists needed, output: NONE
            """, qaReport, existingTasks.stream().map(t -> t.getPersona().name()).collect(Collectors.joining(", ")));
            
        String response = blockingCall(prompt).trim();
        
        if (!response.equals("NONE") && response.contains("|")) {
            // List of coverage-related focus areas to filter out
            Set<String> invalidFocusAreas = Set.of("coverage", "module-diversity", "evidence-quality", 
                "sre-risks", "contract-clarity", "module_diversity", "evidence_quality", "sre_risks", "contract_clarity");
            
            for (String line : response.split("\n")) {
                if (line.contains("|")) {
                    String[] parts = line.split("\\|", 3);
                    if (parts.length == 3) {
                        String focusArea = parts[1].trim();
                        String question = parts[2].trim();
                        
                        // Filter out coverage-related focus areas and questions
                        String focusLower = focusArea.toLowerCase();
                        String questionLower = question.toLowerCase();
                        
                        if (invalidFocusAreas.stream().anyMatch(focusLower::contains) ||
                            questionLower.contains("coverage") || questionLower.contains("file count") ||
                            questionLower.contains("evidence quantity") || questionLower.contains("module diversity")) {
                            log.warn("Skipping coverage-related specialist: {} | {}", focusArea, question);
                            continue; // Skip this specialist
                        }
                        
                        try {
                            WorkerTask specialist = WorkerTask.builder()
                                .taskId(UUID.randomUUID().toString())
                                .persona(WorkerPersona.valueOf(parts[0].trim()))
                                .focusArea(focusArea)
                                .specificQuestion(question)
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
    
    private boolean refineTasksBasedOnQA(List<WorkerTask> tasks, String qaReport, int iteration, CurrentExecutionPlan executionPlan, Consumer<String> progressConsumer, Runnable onCriticalCoverage) {
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
        
        // ENHANCED: Parse coverage metrics from QA report and check thresholds
        QueryIntentAnalyzer.QueryIntent queryIntent = executionPlan.getQueryIntent();
        LexicalScoutAgent.DomainMap domainMap = executionPlan.getDomainMap();
        boolean isComprehensive = queryIntent != null && queryIntent.getMode() == QueryIntentAnalyzer.QueryIntent.Mode.COMPREHENSIVE;
        
        boolean needsRefinement = false;
        boolean coverageIssue = false;
        
        // Extract evidence files count
        Set<String> evidenceFiles = new HashSet<>();
        for (WorkerTask t : tasks) {
            String report = t.getReport() != null ? t.getReport() : "";
            java.util.regex.Pattern evidencePattern = java.util.regex.Pattern.compile("\\*\\*Evidence\\*\\*:\\s*`([^`]+)`");
            java.util.regex.Matcher matcher = evidencePattern.matcher(report);
            while (matcher.find()) {
                evidenceFiles.add(matcher.group(1));
            }
        }
        int filesAnalyzed = evidenceFiles.size();
        int recommendedFiles = queryIntent != null ? queryIntent.getRecommendedTopK() : 50;
        
        // Parse QA report for coverage metrics
        int modulesCovered = 0;
        int totalModules = 0;
        double fileCoveragePercent = 0.0;
        double moduleCoveragePercent = 0.0;
        double actualRepoCoveragePercent = 0.0;
        long totalFilesInRepo = 0;
        
        // Extract module coverage from QA report
        java.util.regex.Pattern modulePattern = java.util.regex.Pattern.compile("(?i)(\\d+)\\s*(?:out of|/)\\s*(\\d+)\\s*(?:modules|module)");
        java.util.regex.Matcher moduleMatcher = modulePattern.matcher(qaReport);
        if (moduleMatcher.find()) {
            modulesCovered = Integer.parseInt(moduleMatcher.group(1));
            totalModules = Integer.parseInt(moduleMatcher.group(2));
            if (totalModules > 0) {
                moduleCoveragePercent = (double) modulesCovered / totalModules * 100;
            }
        }
        
        // Extract file count coverage vs recommended
        java.util.regex.Pattern filePattern = java.util.regex.Pattern.compile("(?i)(\\d+)\\s*(?:files?|file)\\s*(?:analyzed|with evidence)\\s*(?:vs|out of|/)\\s*(\\d+)");
        java.util.regex.Matcher fileMatcher = filePattern.matcher(qaReport);
        if (fileMatcher.find()) {
            int reportedFiles = Integer.parseInt(fileMatcher.group(1));
            int reportedRecommended = Integer.parseInt(fileMatcher.group(2));
            if (reportedRecommended > 0) {
                fileCoveragePercent = (double) reportedFiles / reportedRecommended * 100;
            }
        } else if (recommendedFiles > 0) {
            // Fallback: Use extracted file count vs recommended
            fileCoveragePercent = (double) filesAnalyzed / recommendedFiles * 100;
        }
        
        // Extract ACTUAL repository coverage percentage from QA report
        // Format: "Actual Coverage: %.1f%% of repository analyzed (%d/%d files)"
        // Pattern should match: "Actual Coverage: 5.0% of repository" or "5.0%% of repository"
        java.util.regex.Pattern actualRepoPattern = java.util.regex.Pattern.compile("(?i)(?:actual coverage|coverage assessment).*?(\\d+(?:\\.\\d+)?)\\s*%+\\s*(?:of repository|repository|analyzed)", java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher actualRepoMatcher = actualRepoPattern.matcher(qaReport);
        if (actualRepoMatcher.find()) {
            actualRepoCoveragePercent = Double.parseDouble(actualRepoMatcher.group(1));
            log.debug("Parsed actual repository coverage: {}%", actualRepoCoveragePercent);
        }
        
        // Also extract from format: "X.X%% of repository analyzed (Y/Total files)"
        if (actualRepoCoveragePercent == 0) {
            java.util.regex.Pattern altPattern = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*%+\\s*of\\s+repository\\s+analyzed\\s*\\(\\d+/(\\d+)\\s*files\\)");
            java.util.regex.Matcher altMatcher = altPattern.matcher(qaReport);
            if (altMatcher.find()) {
                actualRepoCoveragePercent = Double.parseDouble(altMatcher.group(1));
                totalFilesInRepo = Long.parseLong(altMatcher.group(2));
                log.debug("Parsed actual repository coverage from alt format: {}% ({} total files)", actualRepoCoveragePercent, totalFilesInRepo);
            }
        }
        
        // Also extract total repository size if available
        // Format: "Repository Size: 1000 total files in project"
        java.util.regex.Pattern repoSizePattern = java.util.regex.Pattern.compile("(?i)(?:repository size|total files).*?:\\s*(\\d+)\\s*(?:total files|files in project|files)");
        java.util.regex.Matcher repoSizeMatcher = repoSizePattern.matcher(qaReport);
        if (repoSizeMatcher.find()) {
            totalFilesInRepo = Long.parseLong(repoSizeMatcher.group(1));
            log.debug("Parsed total repository size: {} files", totalFilesInRepo);
        } else {
            // Fallback: Calculate from actual coverage percent if we have it
            if (actualRepoCoveragePercent > 0 && filesAnalyzed > 0) {
                totalFilesInRepo = (long) (filesAnalyzed / (actualRepoCoveragePercent / 100.0));
                log.debug("Calculated total repository size from coverage: {} files ({} files / {}%)", totalFilesInRepo, filesAnalyzed, actualRepoCoveragePercent);
            }
        }
        
        log.info("Coverage metrics parsed - File vs Recommended: {:.1f}%, Actual Repository: {:.1f}%, Total Repo Files: {}", 
            String.format("%.1f", fileCoveragePercent), String.format("%.1f", actualRepoCoveragePercent), totalFilesInRepo);
        
        // THRESHOLD CHECKS for COMPREHENSIVE and HYBRID queries
        boolean isComprehensiveOrHybrid = isComprehensive || (queryIntent != null && queryIntent.getMode() == QueryIntentAnalyzer.QueryIntent.Mode.HYBRID);
        if (isComprehensiveOrHybrid && domainMap != null && domainMap.getModules() != null && !domainMap.getModules().isEmpty()) {
            List<String> knownModules = domainMap.getModules().stream()
                .limit(10)
                .map(LexicalScoutAgent.ModuleCluster::getModuleName)
                .collect(java.util.stream.Collectors.toList());
            
            totalModules = Math.max(totalModules, knownModules.size());
            if (totalModules == 0) totalModules = knownModules.size();
            
            // Check module coverage threshold (50% for warning, 30% for critical)
            if (moduleCoveragePercent < 30.0 || (totalModules > 0 && modulesCovered < Math.ceil(totalModules * 0.3))) {
                log.warn("Module coverage too low: {}/{} modules ({}%)", modulesCovered, totalModules, String.format("%.1f", moduleCoveragePercent));
                coverageIssue = true;
                needsRefinement = true;
                progressConsumer.accept("⚠️ Architect: Critical module coverage gap detected (" + modulesCovered + "/" + totalModules + " modules). Spawning module-specific workers...");
                spawnModuleWorkers(tasks, domainMap, knownModules, modulesCovered, progressConsumer);
            } else if (moduleCoveragePercent < 50.0 || (totalModules > 0 && modulesCovered < Math.ceil(totalModules * 0.5))) {
                log.warn("Module coverage below threshold: {}/{} modules ({}%)", modulesCovered, totalModules, String.format("%.1f", moduleCoveragePercent));
                coverageIssue = true;
                needsRefinement = true;
                progressConsumer.accept("⚠️ Architect: Module coverage below threshold (" + modulesCovered + "/" + totalModules + " modules). Expanding module search...");
            }
            
            // Check file count coverage threshold (50% for critical, 70% for warning)
            if (fileCoveragePercent < 50.0) {
                log.warn("File coverage too low: {} files vs {} recommended ({}%)", filesAnalyzed, recommendedFiles, String.format("%.1f", fileCoveragePercent));
                coverageIssue = true;
                needsRefinement = true;
                progressConsumer.accept("⚠️ Architect: Critical file coverage gap (" + filesAnalyzed + "/" + recommendedFiles + " files). Expanding search breadth...");
            } else if (fileCoveragePercent < 70.0) {
                log.warn("File coverage below threshold: {} files vs {} recommended ({}%)", filesAnalyzed, recommendedFiles, String.format("%.1f", fileCoveragePercent));
                coverageIssue = true;
                needsRefinement = true;
            }
            
            // CRITICAL: Check ACTUAL repository coverage percentage (<5% is critical, <10% is warning)
            // This is independent of recommended file count - ensures we analyze a meaningful % of the actual codebase
            if (actualRepoCoveragePercent > 0) {
                if (actualRepoCoveragePercent < 5.0) {
                    log.warn("⚠️ CRITICAL: Actual repository coverage extremely low: {}% ({} files / {} total)", 
                        String.format("%.1f", actualRepoCoveragePercent), filesAnalyzed, totalFilesInRepo);
                    coverageIssue = true;
                    needsRefinement = true;
                    if (onCriticalCoverage != null) {
                        onCriticalCoverage.run(); // Notify that critical coverage was detected
                    }
                    progressConsumer.accept("🚨 Architect: CRITICAL - Only " + String.format("%.1f", actualRepoCoveragePercent) + 
                        "% of repository analyzed (" + filesAnalyzed + "/" + totalFilesInRepo + " files). Aggressively expanding analysis...");
                } else if (actualRepoCoveragePercent < 10.0) {
                    log.warn("⚠️ Actual repository coverage low: {}% ({} files / {} total)", 
                        String.format("%.1f", actualRepoCoveragePercent), filesAnalyzed, totalFilesInRepo);
                    coverageIssue = true;
                    needsRefinement = true;
                    progressConsumer.accept("⚠️ Architect: Low repository coverage: " + String.format("%.1f", actualRepoCoveragePercent) + 
                        "% (" + filesAnalyzed + "/" + totalFilesInRepo + " files). Expanding analysis...");
                } else if (actualRepoCoveragePercent < 20.0) {
                    log.info("Actual repository coverage moderate: {}% ({} files / {} total)", 
                        String.format("%.1f", actualRepoCoveragePercent), filesAnalyzed, totalFilesInRepo);
                    // Moderate coverage - still expand but less aggressively
                    coverageIssue = true;
                    needsRefinement = true;
                }
            }
        }
        
        // Check for technical issues (emojis in QA report)
        boolean hasTechnicalIssues = qaReport.contains("❌") || qaReport.contains("⚠️");
        if (hasTechnicalIssues) {
            needsRefinement = true;
        }
        
        // If no issues found (neither coverage nor technical), mark as satisfied
        if (!needsRefinement) {
            log.info("QA report shows no issues. All workers satisfied.");
            tasks.forEach(t -> {
                if (t.getStatus() != null && !t.getStatus().equals("COMPLETED_SATISFIED")) {
                    t.setStatus("COMPLETED_SATISFIED");
                }
            });
            return false;
        }
        
        // REFINEMENT: For coverage issues, update query intent to expand search
        // For technical issues, refine specific workers
        if (coverageIssue && isComprehensiveOrHybrid && queryIntent != null) {
            int currentTopK = queryIntent.getRecommendedTopK();
            int expandedTopK;
            
            // SMART EXPANSION: Adjust targets based on repository size
            // For very large repos (100k+ files), use percentage-based caps
            // For medium repos (10k-100k), use moderate percentage targets
            // For small repos (<10k), use aggressive percentage targets
            
            if (actualRepoCoveragePercent > 0 && actualRepoCoveragePercent < 5.0 && totalFilesInRepo > 0) {
                // Determine expansion target based on repository size
                int targetRepoCoverage;
                int maxCap;
                
                if (totalFilesInRepo >= 100000) {
                    // VERY LARGE REPOS (>100k files): Cap at 1000 files, target 0.5% minimum
                    // For 232k files: 0.5% = 1160 files, but cap at 1000
                    maxCap = 1000;
                    targetRepoCoverage = Math.min((int) (totalFilesInRepo * 0.005), maxCap); // 0.5% or 1000, whichever is smaller
                    log.warn("🚨 LARGE REPO DETECTED ({} files): Using conservative expansion to {} files (0.5% target, capped at {})", 
                        totalFilesInRepo, targetRepoCoverage, maxCap);
                    progressConsumer.accept("🚨 Architect: Large repository detected (" + totalFilesInRepo + " files). Expanding to " + targetRepoCoverage + " files (0.5% target)...");
                } else if (totalFilesInRepo >= 50000) {
                    // LARGE REPOS (50k-100k files): Cap at 800 files, target 1% minimum
                    maxCap = 800;
                    targetRepoCoverage = Math.min((int) (totalFilesInRepo * 0.01), maxCap); // 1% or 800, whichever is smaller
                    log.warn("⚠️ LARGE REPO DETECTED ({} files): Expanding to {} files (1% target, capped at {})", 
                        totalFilesInRepo, targetRepoCoverage, maxCap);
                    progressConsumer.accept("⚠️ Architect: Large repository (" + totalFilesInRepo + " files). Expanding to " + targetRepoCoverage + " files (1% target)...");
                } else if (totalFilesInRepo >= 10000) {
                    // MEDIUM REPOS (10k-50k files): Cap at 500 files, target 2% minimum
                    maxCap = 500;
                    targetRepoCoverage = Math.min((int) (totalFilesInRepo * 0.02), maxCap); // 2% or 500, whichever is smaller
                    log.warn("⚠️ MEDIUM REPO DETECTED ({} files): Expanding to {} files (2% target, capped at {})", 
                        totalFilesInRepo, targetRepoCoverage, maxCap);
                    progressConsumer.accept("⚠️ Architect: Medium repository (" + totalFilesInRepo + " files). Expanding to " + targetRepoCoverage + " files (2% target)...");
                } else {
                    // SMALL REPOS (<10k files): Target 10% or 200 minimum (original logic)
                    targetRepoCoverage = (int) Math.max(totalFilesInRepo * 0.10, 200);
                    log.warn("🚨 AGGRESSIVE EXPANSION: Small repo ({} files), actual coverage {}% is critical. Expanding to {} files (10% target)", 
                        totalFilesInRepo, String.format("%.1f", actualRepoCoveragePercent), targetRepoCoverage);
                    progressConsumer.accept("🚨 Architect: CRITICAL repository coverage detected. Aggressively expanding to " + targetRepoCoverage + " files (targeting 10% of repository)...");
                }
                
                // Also ensure we're targeting at least 2x current files analyzed
                expandedTopK = Math.max(targetRepoCoverage, filesAnalyzed * 2);
                // Final cap: never exceed 1000 files regardless of repo size (performance limit)
                expandedTopK = Math.min(expandedTopK, 1000);
            } 
            // MODERATE EXPANSION for low actual repository coverage (5-10%)
            else if (actualRepoCoveragePercent > 0 && actualRepoCoveragePercent < 10.0 && totalFilesInRepo > 0) {
                int targetRepoCoverage;
                if (totalFilesInRepo >= 100000) {
                    targetRepoCoverage = Math.min((int) (totalFilesInRepo * 0.008), 800); // 0.8% for very large repos
                } else if (totalFilesInRepo >= 50000) {
                    targetRepoCoverage = Math.min((int) (totalFilesInRepo * 0.015), 600); // 1.5% for large repos
                } else if (totalFilesInRepo >= 10000) {
                    targetRepoCoverage = Math.min((int) (totalFilesInRepo * 0.025), 400); // 2.5% for medium repos
                } else {
                    targetRepoCoverage = (int) (totalFilesInRepo * 0.15); // 15% for small repos
                }
                expandedTopK = Math.max(targetRepoCoverage, (int) (currentTopK * 1.5));
                expandedTopK = Math.min(expandedTopK, 1000); // Cap at 1000
                log.warn("⚠️ MODERATE EXPANSION: Actual repo coverage {}% is low ({} files). Expanding to target {} files", 
                    String.format("%.1f", actualRepoCoveragePercent), totalFilesInRepo, expandedTopK);
                progressConsumer.accept("⚠️ Architect: Low repository coverage. Expanding to " + expandedTopK + " files...");
            }
            // STANDARD EXPANSION for coverage vs recommended
            else {
                // Increase by 50% or target 80% of recommended
                expandedTopK = Math.max((int) (currentTopK * 1.5), (int) (recommendedFiles * 0.8));
                expandedTopK = Math.min(expandedTopK, 1000); // Cap at 1000
                log.info("Standard expansion: Expanding search TopK from {} to {} for better coverage", currentTopK, expandedTopK);
                progressConsumer.accept("📊 Expanding search breadth: " + currentTopK + " → " + expandedTopK + " files");
            }
            
            queryIntent.setRecommendedTopK(expandedTopK);
            
            // CRITICAL: When TopK is expanded significantly (50%+ increase) due to low coverage,
            // force all workers to re-execute so they can access the expanded document pool.
            // This ensures we actually analyze more files when coverage is critically low.
            double expansionRatio = (double) expandedTopK / currentTopK;
            if (expandedTopK > currentTopK && expansionRatio >= 1.5 && actualRepoCoveragePercent < 10.0) {
                log.warn("🔄 FORCING RE-EXECUTION: TopK expanded by {:.1f}x ({} → {}) due to low coverage ({}%). All workers will re-execute with expanded pool.", 
                    String.format("%.1f", expansionRatio), currentTopK, expandedTopK, String.format("%.1f", actualRepoCoveragePercent));
                progressConsumer.accept("🔄 Architect: TopK expanded " + String.format("%.1f", expansionRatio) + "x. Re-executing all workers with expanded document pool (" + expandedTopK + " files)...");
                
                // Mark all workers that haven't been marked as satisfied as REFINING
                // This ensures they re-execute in the next iteration with the expanded TopK
                // We preserve their existing questions - they'll just get access to more documents
                int workersMarkedForReexecution = 0;
                for (WorkerTask task : tasks) {
                    if (task.getStatus() == null || !task.getStatus().equals("COMPLETED_SATISFIED")) {
                        // Don't change the question, just mark as REFINING to force re-execution
                        // The worker will use the updated TopK from queryIntent when it executes
                        if (!task.getStatus().equals("REFINING")) {
                            task.setStatus("REFINING");
                            workersMarkedForReexecution++;
                            log.debug("Marked worker {} for re-execution with expanded TopK (preserving question: {})", 
                                task.getPersona().name(), task.getSpecificQuestion());
                        }
                    }
                }
                log.info("Marked {} workers for re-execution with expanded TopK", workersMarkedForReexecution);
            }
        }
        
        // Refine individual workers based on QA critique (existing logic)
        for (WorkerTask task : tasks) {
            // Skip refinement for workers that found no code
            if (task.getReport() != null && task.getReport().contains("No relevant code found")) {
                task.setStatus("COMPLETED_SATISFIED");
                continue;
            }
            
            // Only refine workers if QA specifically criticizes them OR if we need general refinement
            if (hasTechnicalIssues || coverageIssue) {
                // Check if this specific agent was criticized in the report
                String coverageGuidance = coverageIssue ? """
                    
                    **CRITICAL: DO NOT ask about coverage or file counts. DO NOT ask meta-questions like:
                    - "What additional modules should be included to improve coverage?"
                    - "What steps are being taken to ensure coverage?"
                    - "How can we improve coverage of findings?"
                    
                    Instead, ask DOMAIN-SPECIFIC questions about actual business functionality:
                    - "What patient management workflows are implemented in the medication dispense module?"
                    - "How does the program workflow handle patient enrollment and state transitions?"
                    - "What business rules govern the voiding process for diagnoses?"
                    """ : "";
                
                String updatePrompt = String.format("""
                    You are the Head Architect (Iteration %d/4).
                    Worker: %s (Focus: %s)
                    Previous Report: %s
                    
                    QA Critique:
                    %s
                    
                    Instruction:
                    If this is a COMPREHENSIVE query with coverage issues, generate a NEW question about a SPECIFIC business domain or capability that was NOT covered yet.
                    DO NOT ask meta-questions about coverage itself. Instead, ask about actual business functionality.
                    If this is a technical issue (type mismatch, SRE risk), focus on that specific problem.
                    If the QA Critique highlights missing info for THIS worker, generate a NEW, CONCISE Question (one sentence).
                    Focus on: %s%s
                    If the report is fine and coverage is adequate, output "SATISFIED".
                    
                    Output ONLY the question or "SATISFIED", nothing else:
                    """, iteration, task.getPersona().name(), task.getFocusArea(), task.getReport(), qaReport,
                    coverageIssue ? "A specific uncovered business domain or capability (NOT coverage metrics)" :
                    iteration == 2 ? "Evidence and specific file citations" :
                    iteration == 3 ? "Cross-references with other agents' findings" :
                    "Final validation and completeness",
                    coverageGuidance);
                    
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
            } else {
                task.setStatus("COMPLETED_SATISFIED");
            }
        }
        
        return tasks.stream().anyMatch(t -> t.getStatus() != null && t.getStatus().equals("REFINING"));
    }
    
    /**
     * Spawn workers for missing modules when coverage is low
     */
    private void spawnModuleWorkers(List<WorkerTask> existingTasks, LexicalScoutAgent.DomainMap domainMap, 
                                    List<String> knownModules, int modulesCovered, Consumer<String> progressConsumer) {
        if (domainMap == null || domainMap.getModules() == null || domainMap.getModules().isEmpty()) {
            return;
        }
        
        // Find modules that don't have coverage yet
        Set<String> coveredModuleNames = new HashSet<>();
        for (WorkerTask task : existingTasks) {
            String report = task.getReport() != null ? task.getReport() : "";
            // Try to infer module from focus area or report
            for (String module : knownModules) {
                if (task.getFocusArea().toLowerCase().contains(module.toLowerCase()) || 
                    report.toLowerCase().contains(module.toLowerCase())) {
                    coveredModuleNames.add(module);
                }
            }
        }
        
        // Filter out package-like module names (e.g., "de.metas.adempiere.adempiere" or Java package paths)
        // These are likely package names, not actual business modules
        java.util.function.Predicate<String> isValidModule = m -> {
            String lower = m.toLowerCase();
            // Filter out:
            // 1. Java package paths (contain multiple dots and are >20 chars)
            // 2. Generic technical terms
            // 3. Coverage-related terms
            if (m.contains(".") && m.length() > 20) {
                int dotCount = (int) m.chars().filter(ch -> ch == '.').count();
                if (dotCount >= 2) {
                    return false; // Likely a package path like "de.metas.adempiere.adempiere"
                }
            }
            if (lower.contains("coverage") || lower.contains("module-diversity") || 
                lower.contains("evidence-quality") || lower.contains("sre-risks") ||
                lower.contains("contract-clarity")) {
                return false; // Skip coverage-related focus areas
            }
            return true;
        };
        
        // Spawn workers for uncovered modules (limit to avoid too many workers)
        List<String> uncoveredModules = knownModules.stream()
            .filter(m -> !coveredModuleNames.contains(m))
            .filter(isValidModule) // Filter out package-like names
            .limit(3) // Spawn max 3 additional workers
            .collect(java.util.stream.Collectors.toList());
        
        for (String module : uncoveredModules) {
            // Clean module name: remove package prefixes and normalize
            String cleanModuleName = module;
            if (module.contains(".")) {
                // Extract last meaningful part (e.g., "adempiere" from "de.metas.adempiere.adempiere")
                String[] parts = module.split("\\.");
                if (parts.length > 1) {
                    // Take the last 2 parts if they're meaningful, otherwise just last part
                    cleanModuleName = parts.length >= 2 && parts[parts.length - 2].length() > 3 
                        ? parts[parts.length - 2] + "-" + parts[parts.length - 1]
                        : parts[parts.length - 1];
                }
            }
            cleanModuleName = cleanModuleName.replace("-module", "").replace("_", "-");
            
            WorkerTask moduleWorker = WorkerTask.builder()
                .taskId(UUID.randomUUID().toString())
                .persona(WorkerPersona.LOGIC_EXTRACTOR) // Use logic extractor for module discovery
                .focusArea(cleanModuleName + "-module")
                // Ask about actual business functionality, NOT coverage
                .specificQuestion("What business domains, workflows, and capabilities are implemented in the " + cleanModuleName + " area of the system? What are the key business use cases?")
                .status("PENDING")
                .attemptCount(0)
                .build();
            existingTasks.add(moduleWorker);
            progressConsumer.accept("➕ Spawned module worker: " + module);
            log.info("Spawned module worker for: {}", module);
        }
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
        
        if (domainOrProject == null || domainOrProject.isEmpty() || domainOrProject.equalsIgnoreCase("General")) {
            log.warn("No domain/project specified for filtering. Returning all documents (potential data leak risk!)");
            return docs; // No filtering if domain is null/empty/General
        }
        
        // Find all projects that match the domain/project name
        List<Project> matchingProjects = new ArrayList<>();
        List<Project> projectsByName = projectRepository.findByNameContainingIgnoreCase(domainOrProject);
        List<Project> projectsByDomain = projectRepository.findByDomain(domainOrProject);
        
        matchingProjects.addAll(projectsByName);
        matchingProjects.addAll(projectsByDomain);
        
        // Remove duplicates by project ID
        Set<UUID> matchingProjectIds = matchingProjects.stream()
            .map(Project::getId)
            .collect(java.util.stream.Collectors.toSet());
        
        log.info("Found {} matching projects for domain/project '{}': {}", 
            matchingProjectIds.size(), domainOrProject, 
            matchingProjects.stream().map(Project::getName).collect(java.util.stream.Collectors.joining(", ")));
        
        // STRICT MATCHING: Only include documents from matching projects
        // Use exact project ID match instead of substring matching
        for (Document doc : docs) {
            String sid = (String) doc.getMetadata().get("symbol_id");
            if (sid != null) {
                symbolRepository.findById(UUID.fromString(sid)).ifPresent(symbol -> {
                    if (symbol.getSourceFile() != null && symbol.getSourceFile().getProject() != null) {
                        UUID projectId = symbol.getSourceFile().getProject().getId();
                        
                        // STRICT MATCH: Only include if project ID is in the matching set
                        if (matchingProjectIds.contains(projectId)) {
                            filtered.add(doc);
                        }
                    }
                });
            }
        }
        
        log.info("Filtered {} documents to {} matching project '{}' (strict project ID matching)", 
            docs.size(), filtered.size(), domainOrProject);
        
        if (filtered.isEmpty() && !docs.isEmpty()) {
            log.warn("⚠️ Filtered all documents! No documents matched project '{}'. " +
                "This might indicate a domain/project name mismatch.", domainOrProject);
        }
        
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
            log.debug("Applying Qdrant domain filter: domain == '{}'", domain);
            primaryBuilder.filterExpression("domain == '" + domain + "'");
            primaryResults = vectorStore.similaritySearch(primaryBuilder.build());
            log.debug("Qdrant filtered search returned {} results for domain '{}'", primaryResults.size(), domain);
            
            if (primaryResults.isEmpty()) {
                log.warn("⚠️ Qdrant filter returned 0 results for domain '{}'. Falling back to unfiltered search with manual filtering.", domain);
                // Fallback: retry without filter, then manually filter by project
                var unfiltered = SearchRequest.builder().query(searchQuery).topK(intent.getRecommendedTopK() / 2).build();
                List<Document> unfilteredResults = vectorStore.similaritySearch(unfiltered);
                log.debug("Unfiltered search returned {} results. Applying manual project filtering...", unfilteredResults.size());
                primaryResults = filterByProjectAssociation(unfilteredResults, domain);
            } else {
                // Verify that results actually match the domain (double-check filtering)
                primaryResults = filterByProjectAssociation(primaryResults, domain);
            }
        } else {
            log.warn("⚠️ No domain specified for filtering. Querying all projects (potential data leak risk!)");
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
            log.debug("Applying Qdrant domain filter (COMPREHENSIVE): domain == '{}'", domain);
            primaryBuilder.filterExpression("domain == '" + domain + "'");
            primaryResults = vectorStore.similaritySearch(primaryBuilder.build());
            log.debug("Qdrant filtered search (COMPREHENSIVE) returned {} results for domain '{}'", primaryResults.size(), domain);
            
            if (primaryResults.isEmpty()) {
                // Fallback: retry without filter, then manually filter
                log.warn("⚠️ Domain filter '{}' returned 0 results in comprehensive mode, retrying without filter and applying manual filtering", domain);
                var unfiltered = SearchRequest.builder().query(searchQuery).topK((int) (intent.getRecommendedTopK() * 0.4)).build();
                List<Document> unfilteredResults = vectorStore.similaritySearch(unfiltered);
                log.debug("Unfiltered search (COMPREHENSIVE) returned {} results. Applying manual project filtering...", unfilteredResults.size());
                primaryResults = filterByProjectAssociation(unfilteredResults, domain);
            } else {
                // Verify that results actually match the domain (double-check filtering)
                primaryResults = filterByProjectAssociation(primaryResults, domain);
            }
        } else {
            log.warn("⚠️ No domain specified for filtering (COMPREHENSIVE). Querying all projects (potential data leak risk!)");
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

    private String runDeepQACheck(List<WorkerTask> results, CurrentExecutionPlan executionPlan, Consumer<String> progressConsumer) {
        if (progressConsumer != null) progressConsumer.accept("🕵🏻 QA Agent: Auditing Quality, Coverage & Completeness...");
        
        StringBuilder sourceMaterial = new StringBuilder();
        Set<String> evidenceFiles = new HashSet<>();
        Set<String> modulesCovered = new HashSet<>();
        
        for (WorkerTask t : results) {
            sourceMaterial.append("\n=== SOURCE: ").append(t.getPersona().name()).append(" ===\n")
                          .append(t.getReport()).append("\n");
            
            // Extract evidence files from report
            String report = t.getReport() != null ? t.getReport() : "";
            java.util.regex.Pattern evidencePattern = java.util.regex.Pattern.compile("\\*\\*Evidence\\*\\*:\\s*`([^`]+)`");
            java.util.regex.Matcher matcher = evidencePattern.matcher(report);
            while (matcher.find()) {
                evidenceFiles.add(matcher.group(1));
            }
        }
        
        // Extract module hints from file paths (after collecting all evidence files)
        for (String file : evidenceFiles) {
            String[] parts = file.split("/");
            if (parts.length > 2) {
                // Try to identify module from path (e.g., com/company/module/...)
                for (int i = 0; i < parts.length - 1; i++) {
                    if (parts[i].matches("^[a-z]+(-[a-z]+)*$") && parts[i].length() > 3) {
                        modulesCovered.add(parts[i]);
                    }
                }
            }
        }
        
        QueryIntentAnalyzer.QueryIntent queryIntent = executionPlan.getQueryIntent();
        LexicalScoutAgent.DomainMap domainMap = executionPlan.getDomainMap();
        
        String queryMode = queryIntent != null ? queryIntent.getMode().name() : "FOCUSED";
        boolean isComprehensive = queryIntent != null && queryIntent.getMode() == QueryIntentAnalyzer.QueryIntent.Mode.COMPREHENSIVE;
        int recommendedTopK = queryIntent != null ? queryIntent.getRecommendedTopK() : 50;
        
        // Get total repository size for coverage percentage calculation
        long totalFilesInRepo = 0;
        String repoSizeInfo = "";
        String domain = executionPlan.getDomain();
        if (domain != null && !domain.isEmpty() && !domain.equalsIgnoreCase("General")) {
            try {
                List<Project> projects = projectRepository.findByDomain(domain);
                if (!projects.isEmpty()) {
                    // Sum total files across all projects in this domain
                    for (Project project : projects) {
                        totalFilesInRepo += sourceFileRepository.countByProject_Id(project.getId());
                    }
                    
                    if (totalFilesInRepo > 0) {
                        double actualCoveragePercent = (double) evidenceFiles.size() / totalFilesInRepo * 100;
                        repoSizeInfo = String.format(
                            "\n                   - **Repository Size**: %d total files in project\n" +
                            "                   - **Actual Coverage**: %.1f%% of repository analyzed (%d/%d files)\n" +
                            "                   - **Coverage Assessment**: %s\n",
                            totalFilesInRepo,
                            actualCoveragePercent,
                            evidenceFiles.size(),
                            totalFilesInRepo,
                            actualCoveragePercent < 5.0 ? "❌ CRITICAL - Extremely low coverage (<5%)" :
                            actualCoveragePercent < 10.0 ? "⚠️ WARNING - Low coverage (<10%)" :
                            actualCoveragePercent < 20.0 ? "⚠️ WARNING - Moderate coverage (<20%)" :
                            "✅ Acceptable coverage (≥20%)"
                        );
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to get repository size for domain {}: {}", domain, e.getMessage());
            }
        }
        
        String coverageSection = "";
        boolean isComprehensiveOrHybrid = isComprehensive || (queryIntent != null && queryIntent.getMode() == QueryIntentAnalyzer.QueryIntent.Mode.HYBRID);
        if (isComprehensiveOrHybrid && domainMap != null && domainMap.getModules() != null && !domainMap.getModules().isEmpty()) {
            List<String> knownModules = domainMap.getModules().stream()
                .limit(10)
                .map(LexicalScoutAgent.ModuleCluster::getModuleName)
                .collect(java.util.stream.Collectors.toList());
            
            coverageSection = String.format("""
                
                **COVERAGE & COMPLETENESS CHECKS** (CRITICAL for COMPREHENSIVE/HYBRID queries):
                4. **Module Diversity**: Does evidence cover multiple modules/domains? 
                   - Known modules in codebase: %s
                   - Modules covered in evidence: %s
                   - Flag ⚠️ if <50%% of major modules have evidence, ❌ if <30%%
                5. **Evidence Quantity vs Recommended**: 
                   - Total unique files with evidence: %d
                   - Recommended for %s mode: %d+ files
                   - Coverage vs recommended: %.1f%%
                   - Flag ⚠️ if <70%% of recommended, ❌ if <50%%%s
                6. **Actual Repository Coverage**:%s
                7. **Domain Representativeness**: Are all major business domains represented?
                   - Check if evidence spans multiple domains (Sales, Finance, HR, etc.)
                   - Flag ⚠️ if only 1-2 domains covered, ❌ if single domain dominates
                """, 
                knownModules.toString(),
                modulesCovered.toString(),
                evidenceFiles.size(),
                queryMode,
                recommendedTopK,
                recommendedTopK > 0 ? (double) evidenceFiles.size() / recommendedTopK * 100 : 0.0,
                recommendedTopK > 0 ? 
                    ((double) evidenceFiles.size() / recommendedTopK < 0.5 ? " - ❌ CRITICAL" :
                     (double) evidenceFiles.size() / recommendedTopK < 0.7 ? " - ⚠️ WARNING" : "") : "",
                repoSizeInfo.isEmpty() ? "\n                   - Repository size unknown (cannot calculate actual coverage %)" : repoSizeInfo);
        } else {
            coverageSection = """
                
                **COVERAGE CHECK** (for FOCUSED queries):
                4. **Evidence Sufficiency**: Does the evidence adequately answer the specific question?
                   - Flag ⚠️ if evidence is sparse or unclear
                   - Flag ❌ if critical evidence is missing for the specific query
                """;
        }
        
        String prompt = String.format("""
            You are the Lead SRE and QA Architect.
            Query Mode: %s
            Task: Perform comprehensive quality audit including technical correctness, evidence quality, and coverage.
            
            Source Reports:
            %s
            
            **CRITICAL INSTRUCTIONS**:
            1. **Detect Type Mismatches**: Does the Backend say "Returns Integer" but Frontend says "Expects JSON"?
               - Flag ❌ for incompatible types
               - Flag ⚠️ for ambiguous or unclear type contracts
            2. **Identify SRE Risks**: Are there synchronous dependencies (e.g. OrderService calls PaymentService blocking)?
               - Flag ❌ for blocking calls that could cause cascading failures
               - Flag ⚠️ for potential performance bottlenecks
            3. **Verify Boundary Contracts**: Do REST paths match REDUX Action payloads? Do API contracts match?
               - Flag ❌ for mismatched contracts
               - Flag ⚠️ for missing or unclear contracts
            %s
            7. **Evidence Quality**: For each worker report, assess:
               - Are code citations specific (include line numbers)?
               - Are code snippets accurate and relevant?
               - Are claims backed by evidence?
               - Flag ⚠️ for weak evidence, ❌ for missing or incorrect evidence
            8. **Report Completeness**: 
               - Do reports address the assigned questions?
               - Are findings actionable and specific?
               - Flag ⚠️ for vague or incomplete answers, ❌ for unanswered questions
            
            Output a comprehensive "QA Audit Log" identifying all issues.
            Use emojis: ❌ for Critical Issues, ⚠️ for Warnings, ✅ for Good.
            
            **IMPORTANT**: For COMPREHENSIVE queries, coverage and module diversity are CRITICAL.
            For FOCUSED queries, evidence quality and specificity are more important than breadth.
            """, 
            queryMode,
            sourceMaterial.toString(),
            coverageSection);
            
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
