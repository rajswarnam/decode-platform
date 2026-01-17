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
}
