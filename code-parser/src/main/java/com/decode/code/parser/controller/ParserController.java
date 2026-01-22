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
        
        // Trigger parsing for each project asynchronously
        int triggeredCount = 0;
        for (Project project : projects) {
            try {
                Thread projectThread = new Thread(() -> {
                    try {
                        log.info("🔄 [GROUP THREAD START] Starting parsing for project: {} (ID: {})", project.getName(), project.getId());
                        parserService.processProject(project);
                        log.info("✅ [GROUP THREAD COMPLETE] Successfully completed parsing for project: {}", project.getName());
                    } catch (Exception e) {
                        log.error("❌ [GROUP THREAD ERROR] Error during parsing for project {} (ID: {}): {}", 
                            project.getName(), project.getId(), e.getMessage(), e);
                        log.error("❌ [GROUP THREAD ERROR] Stack trace:", e);
                    } catch (Throwable t) {
                        log.error("❌ [GROUP THREAD FATAL] Fatal error during parsing for project {} (ID: {}): {}", 
                            project.getName(), project.getId(), t.getMessage(), t);
                    }
                }, "GroupParserThread-" + project.getName() + "-" + project.getId());
                
                projectThread.setDaemon(false);
                projectThread.start();
                log.info("🚀 [GROUP THREAD LAUNCHED] Started parsing thread for project: {} (Thread: {})", 
                    project.getName(), projectThread.getName());
                triggeredCount++;
            } catch (Exception e) {
                log.error("❌ [GROUP THREAD CREATE ERROR] Failed to create/start thread for project {}: {}", 
                    project.getName(), e.getMessage(), e);
            }
        }
        
        Map<String, Object> response = new HashMap<>();
        response.put("message", "Parsing triggered for " + triggeredCount + " projects in group: " + groupName);
        response.put("groupName", groupName);
        response.put("projectsFound", projects.size());
        response.put("projectsTriggered", triggeredCount);
        response.put("projectNames", projects.stream().map(Project::getName).collect(java.util.stream.Collectors.toList()));
        
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
