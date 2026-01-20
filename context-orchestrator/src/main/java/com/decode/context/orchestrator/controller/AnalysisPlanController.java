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
    
    /**
     * Calculate evidence quality score based on:
     * 1. File coverage (files analyzed vs recommended minimum)
     * 2. Iteration number (early iterations have lower scores)
     * 3. Task completion with evidence
     * 
     * Score ranges from 0-10, where:
     * - 0-3: Critical (very few files, early iteration)
     * - 4-6: Low (below recommended file count)
     * - 7-8: Moderate (meets minimum requirements)
     * - 9-10: High (exceeds recommendations, multiple iterations)
     */
    private double calculateEvidenceQuality(AgentOrchestrator.CurrentExecutionPlan plan) {
        if (plan.getTasks().isEmpty()) return 0.0;
        
        // Extract all unique files analyzed
        List<String> allFiles = plan.getTasks().stream()
            .flatMap(t -> extractFileNames(t.getReport()).stream())
            .distinct()
            .collect(Collectors.toList());
        int filesAnalyzed = allFiles.size();
        
        // Count tasks with evidence
        long tasksWithEvidence = plan.getTasks().stream()
            .filter(t -> t.getReport() != null && t.getReport().contains("**Evidence**:"))
            .count();
        
        // Get max iteration number (attemptCount + 1)
        int maxIteration = plan.getTasks().stream()
            .mapToInt(t -> t.getAttemptCount() + 1)
            .max()
            .orElse(1);
        
        // Recommended minimum files based on query type
        // FOCUSED: 50-100, HYBRID: 100-200, COMPREHENSIVE: 200-500
        // Use conservative minimum of 50 files for good evidence quality
        int recommendedMinFiles = 50;
        
        // Calculate file coverage score (0-5 points)
        double fileCoverageScore;
        if (filesAnalyzed >= recommendedMinFiles) {
            // 5 points if meets or exceeds recommendation
            fileCoverageScore = 5.0;
        } else if (filesAnalyzed >= recommendedMinFiles * 0.7) {
            // 4 points if 70%+ of recommendation
            fileCoverageScore = 4.0;
        } else if (filesAnalyzed >= recommendedMinFiles * 0.5) {
            // 3 points if 50%+ of recommendation
            fileCoverageScore = 3.0;
        } else if (filesAnalyzed >= recommendedMinFiles * 0.3) {
            // 2 points if 30%+ of recommendation
            fileCoverageScore = 2.0;
        } else {
            // 1 point if less than 30% of recommendation
            fileCoverageScore = 1.0;
        }
        
        // Calculate task evidence score (0-3 points)
        double taskEvidenceScore = (double) tasksWithEvidence / plan.getTasks().size() * 3.0;
        
        // Calculate iteration penalty (0-2 points deduction for early iterations)
        double iterationPenalty = 0.0;
        if (maxIteration == 1) {
            iterationPenalty = 2.0; // First iteration: -2 points
        } else if (maxIteration == 2) {
            iterationPenalty = 1.0; // Second iteration: -1 point
        }
        // Iteration 3+: No penalty
        
        // Final score: file coverage + task evidence - iteration penalty
        double finalScore = fileCoverageScore + taskEvidenceScore - iterationPenalty;
        
        // Cap at 0-10 range
        finalScore = Math.max(0.0, Math.min(10.0, finalScore));
        
        log.debug("Evidence quality calculation: files={}, recommended={}, tasksWithEvidence={}/{}, iteration={}, score={:.1f}",
                filesAnalyzed, recommendedMinFiles, tasksWithEvidence, plan.getTasks().size(), maxIteration, finalScore);
        
        return finalScore;
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
