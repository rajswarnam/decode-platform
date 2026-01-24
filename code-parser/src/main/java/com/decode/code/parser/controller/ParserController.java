package com.decode.code.parser.controller;

import com.decode.code.parser.domain.Project;
import com.decode.code.parser.repository.ProjectRepository;
import com.decode.code.parser.repository.SourceFileRepository;
import com.decode.code.parser.repository.SymbolRepository;
import com.decode.code.parser.service.ParserOrchestratorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping({"/api/parser", "/api/v1/parser"}) // Support both paths for backward compatibility
@RequiredArgsConstructor
@Slf4j
public class ParserController {

    private final ProjectRepository projectRepository;
    private final SymbolRepository symbolRepository;
    private final SourceFileRepository sourceFileRepository;
    private final ParserOrchestratorService parserService;

    @PostMapping("/trigger")
    public ResponseEntity<Map<String, Object>> triggerParsing(@RequestParam UUID projectId) {
        // IMMEDIATE SYNCHRONOUS LOGGING - This MUST appear in logs
        System.out.println("========================================");
        System.out.println("TRIGGER ENDPOINT CALLED - Project ID: " + projectId);
        System.out.println("========================================");
        log.info("========================================");
        log.info("📥 [TRIGGER ENDPOINT] Received trigger for parsing project ID: {}", projectId);
        log.info("📥 [TRIGGER ENDPOINT] Thread: {}, Timestamp: {}", Thread.currentThread().getName(), System.currentTimeMillis());
        log.info("========================================");
        
        try {
            java.util.Optional<Project> projectOpt = projectRepository.findById(projectId);
            
            if (projectOpt.isEmpty()) {
                log.error("❌ [TRIGGER ENDPOINT] Project with ID {} not found in code-parser database.", projectId);
                java.util.List<String> availableProjects = projectRepository.findAll().stream()
                    .map(Project::getName)
                    .collect(java.util.stream.Collectors.toList());
                log.error("❌ [TRIGGER ENDPOINT] Available projects: {}", availableProjects);
                
                Map<String, Object> error = new HashMap<>();
                error.put("error", "Project not found. Project may need to be synced to code-parser database.");
                error.put("projectId", projectId.toString());
                error.put("availableProjects", availableProjects);
                return ResponseEntity.status(org.springframework.http.HttpStatus.NOT_FOUND).body(error);
            }
            
            Project project = projectOpt.get();
            log.info("✅ [TRIGGER ENDPOINT] Found project: {} (ID: {})", project.getName(), projectId);
            
            // Check current status before parsing
            long symbolCountBefore = symbolRepository.countBySourceFile_Project_Id(projectId);
            log.info("📊 [TRIGGER ENDPOINT] Current symbol count: {}", symbolCountBefore);
            
            // Create thread with explicit error handling
            final Project finalProject = project; // Make effectively final for lambda
            final long finalSymbolCountBefore = symbolCountBefore;
            
            Thread parsingThread = new Thread(() -> {
                // IMMEDIATE LOGGING IN THREAD
                System.out.println("THREAD STARTED: " + Thread.currentThread().getName());
                log.info("========================================");
                log.info("🔄 [THREAD START] Thread started for project: {} (ID: {})", finalProject.getName(), projectId);
                log.info("🔄 [THREAD] Thread ID: {}, Thread Name: {}", Thread.currentThread().getId(), Thread.currentThread().getName());
                log.info("========================================");
                
                try {
                    // Call the parser service
                    log.info("🔄 [THREAD] About to call parserService.processProject()");
                    parserService.processProject(finalProject);
                    log.info("🔄 [THREAD] parserService.processProject() completed");
                    
                    long symbolCountAfter = symbolRepository.countBySourceFile_Project_Id(projectId);
                    log.info("✅ [THREAD COMPLETE] Parsing completed for project: {}. Symbols: {} → {}", 
                        finalProject.getName(), finalSymbolCountBefore, symbolCountAfter);
                } catch (Exception e) {
                    System.err.println("THREAD ERROR: " + e.getMessage());
                    e.printStackTrace();
                    log.error("❌ [THREAD ERROR] Error during parsing for project {} (ID: {}): {}", 
                        finalProject.getName(), projectId, e.getMessage(), e);
                    log.error("❌ [THREAD ERROR] Exception type: {}", e.getClass().getName());
                    log.error("❌ [THREAD ERROR] Stack trace:", e);
                } catch (Throwable t) {
                    System.err.println("THREAD FATAL ERROR: " + t.getMessage());
                    t.printStackTrace();
                    log.error("❌ [THREAD FATAL] Fatal error during parsing for project {} (ID: {}): {}", 
                        finalProject.getName(), projectId, t.getMessage(), t);
                } finally {
                    log.info("🏁 [THREAD] Thread finishing for project: {}", finalProject.getName());
                }
            }, "ParserThread-" + project.getName() + "-" + projectId);
            
            parsingThread.setDaemon(false);
            parsingThread.setUncaughtExceptionHandler((t, e) -> {
                System.err.println("UNCAUGHT EXCEPTION IN THREAD: " + t.getName());
                e.printStackTrace();
                log.error("❌ [UNCAUGHT EXCEPTION] Thread: {}, Error: {}", t.getName(), e.getMessage(), e);
            });
            
            log.info("🚀 [TRIGGER ENDPOINT] About to start thread: {}", parsingThread.getName());
            parsingThread.start();
            log.info("🚀 [TRIGGER ENDPOINT] Thread started successfully: {}", parsingThread.getName());
            log.info("🚀 [TRIGGER ENDPOINT] Thread state: {}, isAlive: {}", parsingThread.getState(), parsingThread.isAlive());
            
            Map<String, Object> response = new HashMap<>();
            response.put("message", "Parsing triggered for " + project.getName());
            response.put("projectId", projectId.toString());
            response.put("projectName", project.getName());
            response.put("currentSymbolCount", symbolCountBefore);
            response.put("status", "PARSING_IN_PROGRESS");
            response.put("threadName", parsingThread.getName());
            response.put("threadState", parsingThread.getState().toString());
            response.put("note", "Parsing is running asynchronously. Check logs or use /status endpoint to monitor progress.");
            
            log.info("✅ [TRIGGER ENDPOINT] Returning response for project: {}", project.getName());
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            System.err.println("ERROR IN TRIGGER ENDPOINT: " + e.getMessage());
            e.printStackTrace();
            log.error("❌ [TRIGGER ENDPOINT ERROR] Error in trigger endpoint: {}", e.getMessage(), e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Internal error: " + e.getMessage());
            error.put("projectId", projectId.toString());
            return ResponseEntity.status(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getParsingStatus(@RequestParam UUID projectId) {
        Map<String, Object> status = new HashMap<>();
        
        java.util.Optional<Project> projectOpt = projectRepository.findById(projectId);
        if (projectOpt.isEmpty()) {
            status.put("error", "Project not found");
            return ResponseEntity.notFound().build();
        }
        
        Project project = projectOpt.get();
        long symbolCount = symbolRepository.countBySourceFile_Project_Id(projectId);
        
        // Check if project has source files (to distinguish between "not parsed" vs "parsed but no symbols")
        long sourceFileCount = sourceFileRepository.countByProject(project);
        
        status.put("projectName", project.getName());
        status.put("projectId", projectId.toString());
        status.put("symbolCount", symbolCount);
        status.put("sourceFileCount", sourceFileCount);
        
        // Determine status more accurately
        if (symbolCount > 0) {
            status.put("status", "COMPLETED");
            status.put("message", "Parsing completed successfully. Found " + symbolCount + " symbols.");
        } else if (sourceFileCount > 0) {
            status.put("status", "COMPLETED_NO_SYMBOLS");
            status.put("message", "Parsing completed but no symbols were extracted. Check logs for details.");
        } else {
            status.put("status", "PENDING");
            status.put("message", "Parsing not started or no files found. Trigger parsing via /trigger endpoint.");
        }
        
        return ResponseEntity.ok(status);
    }
    
    /**
     * Trigger parsing for all projects in a group
     * Groups are identified by:
     * 1. Project name prefix (e.g., "fusion-master (1)/" matches group "fusion-master (1)")
     * 2. Project name contains group (e.g., "fusion-master (1)/fusion-master/Argo/..." contains "fusion-master (1)")
     * 3. Base path contains the group name
     * 
     * Examples:
     * - groupName="fusion-master (1)" matches: "fusion-master (1)/fusion-master/Argo/SRW/Group/H/Transaction"
     * - groupName="fusion" matches all projects with "fusion" in name or path
     */
    @PostMapping("/trigger/group")
    public ResponseEntity<Map<String, Object>> triggerParsingForGroup(@RequestParam String groupName) {
        log.info("📥 Received trigger for parsing group: {}", groupName);
        
        // Find all projects that match the group name
        // Group matching logic:
        // 1. Project name starts with group name (e.g., "fusion-master (1)/...")
        // 2. Project name contains "/groupName/" pattern
        // 3. Project name contains group name as a distinct segment
        // 4. Base path contains the group name
        java.util.List<Project> projects = projectRepository.findAll().stream()
            .filter(p -> {
                String name = p.getName();
                String basePath = p.getBasePath();
                
                if (name == null && basePath == null) {
                    return false;
                }
                
                // Match if project name starts with group name (most common case)
                if (name != null && name.startsWith(groupName)) {
                    return true;
                }
                
                // Match if project name contains group as a path segment
                // e.g., "fusion-master (1)/fusion-master/..." contains "fusion-master (1)"
                if (name != null && (name.contains("/" + groupName + "/") || name.contains(groupName + "/"))) {
                    return true;
                }
                
                // Match if base path contains group name
                if (basePath != null && basePath.contains(groupName)) {
                    return true;
                }
                
                // Match if group name is a substring (for partial matches)
                // But only if it's a meaningful match (not just a single character)
                if (groupName.length() > 3 && name != null && name.contains(groupName)) {
                    return true;
                }
                
                return false;
            })
            .collect(java.util.stream.Collectors.toList());
        
        if (projects.isEmpty()) {
            log.warn("⚠️ No projects found matching group: {}", groupName);
            Map<String, Object> response = new HashMap<>();
            response.put("error", "No projects found for group: " + groupName);
            response.put("groupName", groupName);
            response.put("projectsFound", 0);
            return ResponseEntity.status(org.springframework.http.HttpStatus.NOT_FOUND).body(response);
        }
        
        log.info("✅ Found {} projects in group '{}'. Triggering parsing...", projects.size(), groupName);
        System.out.println("========================================");
        System.out.println("GROUP TRIGGER: Found " + projects.size() + " projects");
        System.out.println("========================================");
        System.out.flush();
        
        // Use a thread pool to limit concurrent threads (max 20 concurrent)
        int maxConcurrentThreads = Math.min(20, projects.size());
        ExecutorService executor = Executors.newFixedThreadPool(maxConcurrentThreads);
        
        AtomicInteger completedCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        AtomicInteger submittedCount = new AtomicInteger(0);
        
        // Track active threads for visibility
        Map<String, Long> activeThreads = new java.util.concurrent.ConcurrentHashMap<>();
        
        log.info("🚀 [GROUP] Using thread pool with {} concurrent threads for {} projects", maxConcurrentThreads, projects.size());
        System.out.println("========================================");
        System.out.println("Using thread pool with " + maxConcurrentThreads + " concurrent threads");
        System.out.println("About to submit " + projects.size() + " tasks");
        System.out.println("========================================");
        System.out.flush();
        
        // Test the executor with a simple task first
        System.out.println("🧪 [GROUP] Testing executor with a test task...");
        System.out.flush();
        try {
            executor.submit(() -> {
                System.out.println("✅ [GROUP] Executor test task executed successfully!");
                log.info("✅ [GROUP] Executor is working - test task executed");
                System.out.flush();
            }).get(5, TimeUnit.SECONDS); // Wait up to 5 seconds for test task
            System.out.println("✅ [GROUP] Executor test passed - ready to submit real tasks");
            System.out.flush();
        } catch (Exception e) {
            System.err.println("❌ [GROUP] Executor test failed: " + e.getMessage());
            e.printStackTrace();
            System.err.flush();
            log.error("❌ [GROUP] Executor test failed: {}", e.getMessage(), e);
        }
        
        // Submit all tasks to the executor
        for (Project project : projects) {
            final Project finalProject = project;
            final String finalProjectName = project.getName();
            final java.util.UUID finalProjectId = project.getId();
            
            // Increment BEFORE submitting (tracks submission, not execution start)
            int currentSubmitted = submittedCount.incrementAndGet();
            
            // Log progress every submission
            if (currentSubmitted % 10 == 0 || currentSubmitted == 1 || currentSubmitted <= 5) {
                System.out.println("📊 [GROUP] Submitting: " + currentSubmitted + " / " + projects.size());
                log.info("📊 [GROUP] Progress: {}/{} projects submitted to thread pool", currentSubmitted, projects.size());
                System.out.flush();
            }
            
            try {
                executor.submit(() -> {
                // IMMEDIATE LOGGING IN THREAD - BEFORE ANYTHING ELSE
                System.out.println("========================================");
                System.out.println("THREAD STARTED: " + Thread.currentThread().getName());
                System.out.println("Project: " + finalProjectName);
                System.out.println("ID: " + finalProjectId);
                System.out.println("Task #: " + currentSubmitted + "/" + projects.size());
                System.out.println("========================================");
                System.out.flush();
                
                // Log when task actually starts executing
                if (currentSubmitted % 10 == 0 || currentSubmitted == 1 || currentSubmitted <= 5) {
                    System.out.println("📊 [GROUP] Executing: " + currentSubmitted + " / " + projects.size());
                    log.info("📊 [GROUP] Task started executing: {}/{}", currentSubmitted, projects.size());
                    System.out.flush();
                }
                
                log.info("========================================");
                log.info("🔄 [GROUP THREAD START] Thread started for project: {} (ID: {})", finalProjectName, finalProjectId);
                log.info("🔄 [GROUP THREAD] Thread ID: {}, Thread Name: {}, Progress: {}/{}", 
                    Thread.currentThread().getId(), Thread.currentThread().getName(), currentSubmitted, projects.size());
                log.info("========================================");
                
                try {
                    // Track thread start time
                    String threadKey = finalProjectName + "-" + finalProjectId;
                    activeThreads.put(threadKey, System.currentTimeMillis());
                    
                    log.info("🔄 [GROUP THREAD] About to call parserService.processProject() for: {}", finalProjectName);
                    System.out.println("About to call parserService.processProject() for: " + finalProjectName);
                    System.out.flush();
                    
                    long startTime = System.currentTimeMillis();
                    parserService.processProject(finalProject);
                    long duration = System.currentTimeMillis() - startTime;
                    
                    // Remove from active threads
                    activeThreads.remove(threadKey);
                    
                    int completed = completedCount.incrementAndGet();
                    log.info("⏱️ [GROUP THREAD] Project {} completed in {} ms ({} seconds)", 
                        finalProjectName, duration, duration / 1000);
                    log.info("🔄 [GROUP THREAD] parserService.processProject() completed for: {}", finalProjectName);
                    System.out.println("✅ Completed: " + finalProjectName + " (" + completed + "/" + projects.size() + " done)");
                    System.out.flush();
                    
                    // Log progress every 10 completions
                    if (completed % 10 == 0 || completed == projects.size()) {
                        log.info("📊 [GROUP] Progress: {}/{} projects completed, {} errors", completed, projects.size(), errorCount.get());
                        System.out.println("📊 [GROUP] Progress: " + completed + "/" + projects.size() + " completed, " + errorCount.get() + " errors");
                        System.out.flush();
                    }
                    
                    log.info("✅ [GROUP THREAD COMPLETE] Successfully completed parsing for project: {}", finalProjectName);
                } catch (Exception e) {
                    // Remove from active threads on error
                    String threadKey = finalProjectName + "-" + finalProjectId;
                    activeThreads.remove(threadKey);
                    
                    int errors = errorCount.incrementAndGet();
                    System.err.println("❌ THREAD ERROR for " + finalProjectName + ": " + e.getMessage());
                    System.err.println("Errors so far: " + errors);
                    e.printStackTrace();
                    System.err.flush();
                    log.error("❌ [GROUP THREAD ERROR] Error during parsing for project {} (ID: {}): {}", 
                        finalProjectName, finalProjectId, e.getMessage(), e);
                    log.error("❌ [GROUP THREAD ERROR] Exception type: {}", e.getClass().getName());
                } catch (Throwable t) {
                    // Remove from active threads on fatal error
                    String threadKey = finalProjectName + "-" + finalProjectId;
                    activeThreads.remove(threadKey);
                    
                    int errors = errorCount.incrementAndGet();
                    System.err.println("❌ THREAD FATAL ERROR for " + finalProjectName + ": " + t.getMessage());
                    t.printStackTrace();
                    System.err.flush();
                    log.error("❌ [GROUP THREAD FATAL] Fatal error during parsing for project {} (ID: {}): {}", 
                        finalProjectName, finalProjectId, t.getMessage(), t);
                } finally {
                    // Ensure thread is removed from active tracking
                    String threadKey = finalProjectName + "-" + finalProjectId;
                    activeThreads.remove(threadKey);
                    
                    int completed = completedCount.get();
                    System.out.println("🏁 Thread finishing: " + Thread.currentThread().getName() + " (" + completed + "/" + projects.size() + " done)");
                    log.info("🏁 [GROUP THREAD] Thread finishing for project: {} ({}/{})", finalProjectName, completed, projects.size());
                    System.out.flush();
                }
                });
                
                // Log successful submission for first few tasks
                if (currentSubmitted <= 5) {
                    System.out.println("✅ [GROUP] Successfully submitted task #" + currentSubmitted + " for: " + finalProjectName);
                    log.info("✅ [GROUP] Successfully submitted task #{} for: {}", currentSubmitted, finalProjectName);
                    System.out.flush();
                }
            } catch (Exception e) {
                System.err.println("❌ [GROUP] Failed to submit task for " + finalProjectName + ": " + e.getMessage());
                e.printStackTrace();
                System.err.flush();
                log.error("❌ [GROUP] Failed to submit task for {}: {}", finalProjectName, e.getMessage(), e);
                errorCount.incrementAndGet();
            }
        }
        
        System.out.println("========================================");
        System.out.println("✅ [GROUP] Finished submitting all " + submittedCount.get() + " tasks to executor");
        System.out.println("Executor should now start processing tasks...");
        System.out.println("========================================");
        System.out.flush();
        log.info("✅ [GROUP] Finished submitting all {} tasks to executor", submittedCount.get());
        
        // Start a background thread to log progress periodically
        Thread progressLogger = new Thread(() -> {
            try {
                while (completedCount.get() + errorCount.get() < projects.size()) {
                    Thread.sleep(5000); // Log every 5 seconds
                    int completed = completedCount.get();
                    int errors = errorCount.get();
                    int total = completed + errors;
                    int remaining = projects.size() - total;
                    int active = activeThreads.size();
                    
                    log.info("📊 [GROUP PROGRESS] {}/{} projects completed ({} successful, {} errors). Active threads: {}. Remaining: {}", 
                        total, projects.size(), completed, errors, active, remaining);
                    System.out.println("📊 [GROUP PROGRESS] " + total + "/" + projects.size() + " projects completed (" + completed + " successful, " + errors + " errors). Active: " + active + ", Remaining: " + remaining);
                    
                    // Log active threads if there are any (and not too many)
                    if (!activeThreads.isEmpty()) {
                        long currentTime = System.currentTimeMillis();
                        long maxRunningTime = 0;
                        String longestRunningProject = null;
                        
                        // Find the longest running thread
                        for (Map.Entry<String, Long> entry : activeThreads.entrySet()) {
                            long runningTime = (currentTime - entry.getValue()) / 1000; // seconds
                            if (runningTime > maxRunningTime) {
                                maxRunningTime = runningTime;
                                longestRunningProject = entry.getKey();
                            }
                        }
                        
                        if (activeThreads.size() <= 10) {
                            System.out.println("   Active threads (" + activeThreads.size() + "):");
                            activeThreads.forEach((projectName, startTime) -> {
                                long runningTime = (currentTime - startTime) / 1000; // seconds
                                String status = runningTime > 300 ? "⚠️ STUCK (>5min)" : runningTime > 120 ? "⚠️ SLOW (>2min)" : "";
                                System.out.println("   - " + projectName + " (running for " + runningTime + " seconds) " + status);
                            });
                        } else {
                            System.out.println("   (" + activeThreads.size() + " active threads)");
                            if (longestRunningProject != null) {
                                System.out.println("   ⚠️ Longest running: " + longestRunningProject + " (" + maxRunningTime + " seconds)");
                            }
                        }
                        
                        // Warn if threads are stuck
                        if (maxRunningTime > 600) { // 10 minutes
                            System.out.println("   ⚠️⚠️⚠️ WARNING: Some threads have been running for >10 minutes. They may be stuck!");
                        }
                    }
                    
                    System.out.flush();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "GroupProgressLogger");
        progressLogger.setDaemon(true);
        progressLogger.start();
        
        // Don't shutdown executor immediately - let it run
        // We'll use a separate thread to monitor and shutdown when done
        Thread shutdownMonitor = new Thread(() -> {
            try {
                // Wait for all tasks to complete
                executor.shutdown();
                boolean terminated = executor.awaitTermination(1, TimeUnit.HOURS);
                if (terminated) {
                    log.info("✅ [GROUP] All parsing tasks completed. Executor terminated.");
                    System.out.println("✅ [GROUP] All parsing tasks completed");
                } else {
                    log.warn("⚠️ [GROUP] Executor did not terminate within 1 hour. Some tasks may still be running.");
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
        }, "GroupShutdownMonitor");
        shutdownMonitor.setDaemon(true);
        shutdownMonitor.start();
        
        System.out.println("========================================");
        System.out.println("GROUP TRIGGER: All " + projects.size() + " projects submitted to thread pool");
        System.out.println("Thread pool will process " + maxConcurrentThreads + " projects concurrently");
        System.out.println("Check logs for progress updates every 5 seconds");
        System.out.println("Submitted count: " + submittedCount.get());
        System.out.println("========================================");
        System.out.flush();
        
        log.info("📊 [GROUP] Summary - Total: {}, Submitted: {}, Thread Pool Size: {}", 
            projects.size(), submittedCount.get(), maxConcurrentThreads);
        
        // Give executor a moment to start processing first few tasks
        // This ensures we can see initial logs before returning response
        try {
            Thread.sleep(1000); // 1 second delay
            System.out.println("⏳ [GROUP] Waiting 1 second for initial tasks to start...");
            System.out.flush();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Check if any tasks have started
        int started = completedCount.get() + errorCount.get();
        System.out.println("📊 [GROUP] After 1 second: " + started + " tasks have started/completed");
        System.out.flush();
        
        Map<String, Object> response = new HashMap<>();
        response.put("message", "Parsing triggered for " + submittedCount.get() + " projects in group: " + groupName);
        response.put("groupName", groupName);
        response.put("projectsFound", projects.size());
        response.put("projectsTriggered", submittedCount.get());
        response.put("tasksStarted", started);
        response.put("threadPoolSize", maxConcurrentThreads);
        response.put("projectNames", projects.stream().map(Project::getName).limit(10).collect(java.util.stream.Collectors.toList()));
        response.put("note", "Check logs for progress. Tasks are running asynchronously.");
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * Get all projects in a group (without triggering parsing)
     * Useful for previewing which projects will be parsed
     */
    @GetMapping("/group/projects")
    public ResponseEntity<Map<String, Object>> getProjectsInGroup(@RequestParam String groupName) {
        java.util.List<Project> projects = projectRepository.findAll().stream()
            .filter(p -> {
                String name = p.getName();
                String basePath = p.getBasePath();
                
                if (name == null && basePath == null) {
                    return false;
                }
                
                // Match if project name starts with group name
                if (name != null && name.startsWith(groupName)) {
                    return true;
                }
                
                // Match if project name contains group as a path segment
                if (name != null && (name.contains("/" + groupName + "/") || name.contains(groupName + "/"))) {
                    return true;
                }
                
                // Match if base path contains group name
                if (basePath != null && basePath.contains(groupName)) {
                    return true;
                }
                
                // Match if group name is a substring (for partial matches)
                if (groupName.length() > 3 && name != null && name.contains(groupName)) {
                    return true;
                }
                
                return false;
            })
            .collect(java.util.stream.Collectors.toList());
        
        Map<String, Object> response = new HashMap<>();
        response.put("groupName", groupName);
        response.put("projectCount", projects.size());
        response.put("projects", projects.stream().map(p -> {
            Map<String, Object> proj = new HashMap<>();
            proj.put("id", p.getId().toString());
            proj.put("name", p.getName());
            proj.put("basePath", p.getBasePath());
            return proj;
        }).collect(java.util.stream.Collectors.toList()));
        
        return ResponseEntity.ok(response);
    }
}
