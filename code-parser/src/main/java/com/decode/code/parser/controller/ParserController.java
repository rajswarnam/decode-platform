package com.decode.code.parser.controller;

import com.decode.code.parser.domain.Project;
import com.decode.code.parser.repository.ProjectRepository;
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
@RequestMapping("/api/parser")
@RequiredArgsConstructor
@Slf4j
public class ParserController {

    private final ProjectRepository projectRepository;
    private final SymbolRepository symbolRepository;
    private final ParserOrchestratorService parserService;

    @PostMapping("/trigger")
    public ResponseEntity<String> triggerParsing(@RequestParam UUID projectId) {
        log.info("📥 Received trigger for parsing project ID: {}", projectId);
        
        java.util.Optional<Project> projectOpt = projectRepository.findById(projectId);
        
        if (projectOpt.isEmpty()) {
            log.error("❌ Project with ID {} not found in code-parser database. Available projects: {}", 
                projectId, projectRepository.findAll().stream().map(Project::getName).collect(java.util.stream.Collectors.toList()));
            return ResponseEntity.status(org.springframework.http.HttpStatus.NOT_FOUND)
                .body("Project not found. Project may need to be synced to code-parser database.");
        }
        
        Project project = projectOpt.get();
        log.info("✅ Found project: {} (ID: {}). Starting parsing...", project.getName(), projectId);
        
        // Async execution to avoid blocking the HTTP request
        new Thread(() -> {
            try {
                parserService.processProject(project);
            } catch (Exception e) {
                log.error("❌ Error during parsing for project {}: {}", project.getName(), e.getMessage(), e);
            }
        }).start();
        
        return ResponseEntity.ok("Parsing triggered for " + project.getName());
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
        
        status.put("projectName", project.getName());
        status.put("projectId", projectId.toString());
        status.put("symbolCount", symbolCount);
        status.put("status", symbolCount > 0 ? "COMPLETED" : "PENDING");
        
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
                new Thread(() -> {
                    try {
                        log.info("🔄 Starting parsing for project: {} (ID: {})", project.getName(), project.getId());
                        parserService.processProject(project);
                        log.info("✅ Completed parsing for project: {}", project.getName());
                    } catch (Exception e) {
                        log.error("❌ Error during parsing for project {}: {}", project.getName(), e.getMessage(), e);
                    }
                }).start();
                triggeredCount++;
            } catch (Exception e) {
                log.error("❌ Failed to trigger parsing for project {}: {}", project.getName(), e.getMessage());
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
