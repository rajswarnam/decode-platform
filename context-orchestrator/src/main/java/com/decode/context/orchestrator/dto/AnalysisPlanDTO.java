package com.decode.context.orchestrator.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class AnalysisPlanDTO {
    private String sessionId;
    private DomainMap domainMap; // NEW: Lexical Scout findings
    private ArchitectPlan architectPlan;
    private List<WorkerAssignment> workerAssignments;
    private QAChecklist qaChecklist;
    private EvidenceSummary evidenceSummary;
    
    @Data
    @Builder
    public static class ArchitectPlan {
        private String userQuery;
        private String projectContext;
        private List<String> identifiedAreas;
        private int totalTasksPlanned;
    }
    
    @Data
    @Builder
    public static class WorkerAssignment {
        private String taskId;
        private String persona;
        private String focusArea;
        private String specificQuestion;
        private String status;
        private int iteration;
        private List<String> evidenceFiles;
        private String validationErrors;
    }
    
    @Data
    @Builder
    public static class QAChecklist {
        private List<QACheckItem> checks;
    }
    
    @Data
    @Builder
    public static class QACheckItem {
        private int iteration;
        private String checkType;
        private String status; // PASS, WARN, FAIL
        private String details;
    }
    
    @Data
    @Builder
    public static class EvidenceSummary {
        private int totalFilesAnalyzed;
        private List<ModuleCount> topModules;
        private double evidenceQualityScore;
    }
    
    @Data
    @Builder
    public static class ModuleCount {
        private String moduleName;
        private int fileCount;
    }
    
    @Data
    @Builder
    public static class DomainMap {
        private String domainSummary;
        private List<BusinessEntity> topEntities;
        private List<String> domainPatterns;
    }
    
    @Data
    @Builder
    public static class BusinessEntity {
        private String name;
        private int frequency;
        private String category;
    }
}
