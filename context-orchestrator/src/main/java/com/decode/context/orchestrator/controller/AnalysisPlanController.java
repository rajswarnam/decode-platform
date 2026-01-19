package com.decode.context.orchestrator.controller;

import com.decode.context.orchestrator.agent.AgentOrchestrator;
import com.decode.context.orchestrator.dto.AnalysisPlanDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/semantic/plans")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class AnalysisPlanController {

    private final AgentOrchestrator agentOrchestrator;

    @GetMapping("/{sessionId}")
    public ResponseEntity<AnalysisPlanDTO> getAnalysisPlan(@PathVariable String sessionId) {
        log.debug("Fetching analysis plan for session: {}", sessionId);
        
        AgentOrchestrator.CurrentExecutionPlan plan = agentOrchestrator.getCurrentPlan(sessionId);
        
        if (plan == null) {
            return ResponseEntity.notFound().build();
        }
        
        // Convert to DTO
        AnalysisPlanDTO dto = AnalysisPlanDTO.builder()
            .sessionId(sessionId)
            .domainMap(convertDomainMap(plan.getDomainMap())) // NEW
            .architectPlan(AnalysisPlanDTO.ArchitectPlan.builder()
                .userQuery(plan.getUserQuery())
                .projectContext(plan.getProjectContext())
                .identifiedAreas(plan.getTasks().stream()
                    .map(t -> t.getFocusArea())
                    .distinct()
                    .collect(Collectors.toList()))
                .totalTasksPlanned(plan.getTasks().size())
                .build())
            .workerAssignments(plan.getTasks().stream()
                .map(t -> AnalysisPlanDTO.WorkerAssignment.builder()
                    .taskId(t.getTaskId())
                    .persona(t.getPersona().name())
                    .focusArea(t.getFocusArea())
                    .specificQuestion(t.getSpecificQuestion())
                    .status(t.getStatus())
                    .iteration(t.getAttemptCount() + 1)
                    .evidenceFiles(extractFileNames(t.getReport()))
                    .validationErrors(t.getValidationErrors())
                    .build())
                .collect(Collectors.toList()))
            .qaChecklist(AnalysisPlanDTO.QAChecklist.builder()
                .checks(plan.getQaReports().stream()
                    .map((report) -> parseQAReport(report, plan.getQaReports().indexOf(report) + 1))
                    .flatMap(List::stream)
                    .collect(Collectors.toList()))
                .build())
            .evidenceSummary(buildEvidenceSummary(plan))
            .build();
        
        return ResponseEntity.ok(dto);
    }
    
    /**
     * Retrieve stored analysis result by sessionId
     * Used when SSE stream fails but analysis completed successfully
     */
    @GetMapping("/{sessionId}/result")
    public ResponseEntity<Map<String, Object>> getAnalysisResult(@PathVariable String sessionId) {
        log.info("Fetching analysis result for session: {}", sessionId);
        
        AgentOrchestrator.CurrentExecutionPlan plan = agentOrchestrator.getCurrentPlan(sessionId);
        
        if (plan == null) {
            return ResponseEntity.notFound().build();
        }
        
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("sessionId", sessionId);
        result.put("isComplete", plan.isComplete());
        result.put("finalResult", plan.getFinalResult());
        result.put("errorMessage", plan.getErrorMessage());
        result.put("userQuery", plan.getUserQuery());
        result.put("totalTasks", plan.getTasks() != null ? plan.getTasks().size() : 0);
        result.put("completedTasks", plan.getTasks() != null ? 
            plan.getTasks().stream().filter(t -> "COMPLETED".equals(t.getStatus()) || 
                "COMPLETED_SATISFIED".equals(t.getStatus())).count() : 0);
        
        // Build fallback result if no finalResult stored
        if ((plan.getFinalResult() == null || plan.getFinalResult().isEmpty()) && 
            plan.getTasks() != null && !plan.getTasks().isEmpty()) {
            StringBuilder fallback = new StringBuilder("# Analysis Results\n\n");
            for (var task : plan.getTasks()) {
                if (task.getReport() != null && !task.getReport().isEmpty()) {
                    fallback.append("## ").append(task.getPersona().getTitle()).append("\n\n");
                    fallback.append(task.getReport()).append("\n\n");
                }
            }
            result.put("finalResult", fallback.toString());
        }
        
        return ResponseEntity.ok(result);
    }
    
    private List<String> extractFileNames(String report) {
        if (report == null) return List.of();
        
        // Extract filenames from **Evidence**: `filename` patterns
        return report.lines()
            .filter(line -> line.contains("**Evidence**:"))
            .map(line -> {
                int start = line.indexOf('`');
                int end = line.indexOf('`', start + 1);
                if (start != -1 && end != -1) {
                    return line.substring(start + 1, end);
                }
                return null;
            })
            .filter(name -> name != null)
            .distinct()
            .collect(Collectors.toList());
    }
    
    private List<AnalysisPlanDTO.QACheckItem> parseQAReport(String report, int iteration) {
        // Parse QA report for check items
        // Look for ✅, ⚠️, ❌ markers
        return report.lines()
            .filter(line -> line.contains("✅") || line.contains("⚠️") || line.contains("❌"))
            .map(line -> {
                String status = line.contains("✅") ? "PASS" : 
                               line.contains("⚠️") ? "WARN" : "FAIL";
                String checkType = line.replaceAll("[✅⚠️❌]", "").trim();
                
                return AnalysisPlanDTO.QACheckItem.builder()
                    .iteration(iteration)
                    .checkType(checkType.length() > 100 ? checkType.substring(0, 100) : checkType)
                    .status(status)
                    .details(line)
                    .build();
            })
            .collect(Collectors.toList());
    }
    
    private AnalysisPlanDTO.EvidenceSummary buildEvidenceSummary(AgentOrchestrator.CurrentExecutionPlan plan) {
        List<String> allFiles = plan.getTasks().stream()
            .flatMap(t -> extractFileNames(t.getReport()).stream())
            .distinct()
            .collect(Collectors.toList());
        
        return AnalysisPlanDTO.EvidenceSummary.builder()
            .totalFilesAnalyzed(allFiles.size())
            .topModules(List.of()) // TODO: Group by module
            .evidenceQualityScore(calculateEvidenceQuality(plan))
            .build();
    }
    
    private double calculateEvidenceQuality(AgentOrchestrator.CurrentExecutionPlan plan) {
        long tasksWithEvidence = plan.getTasks().stream()
            .filter(t -> t.getReport() != null && t.getReport().contains("**Evidence**:"))
            .count();
        
        if (plan.getTasks().isEmpty()) return 0.0;
        return (double) tasksWithEvidence / plan.getTasks().size() * 10.0;
    }
    
    private AnalysisPlanDTO.DomainMap convertDomainMap(com.decode.context.orchestrator.agent.LexicalScoutAgent.DomainMap scoutMap) {
        if (scoutMap == null) {
            return null;
        }
        
        return AnalysisPlanDTO.DomainMap.builder()
            .domainSummary(scoutMap.getDomainSummary())
            .topEntities(scoutMap.getTopEntities().stream()
                .limit(10)
                .map(e -> AnalysisPlanDTO.BusinessEntity.builder()
                    .name(e.getName())
                    .frequency(e.getFrequency())
                    .category(e.getCategory())
                    .build())
                .collect(Collectors.toList()))
            .domainPatterns(scoutMap.getDomainPatterns())
            .build();
    }
}
