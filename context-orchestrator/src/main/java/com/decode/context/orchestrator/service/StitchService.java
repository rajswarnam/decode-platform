package com.decode.context.orchestrator.service;

import com.decode.context.orchestrator.domain.AclfMapping;
import com.decode.context.orchestrator.domain.Project;
import com.decode.context.orchestrator.repository.AclfMappingRepository;
import com.decode.context.orchestrator.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class StitchService {

    private final AclfMappingRepository aclfMappingRepository;
    private final ProjectRepository projectRepository;

    @Transactional
    public boolean stitch(UUID mappingId, String selectedSymbolId) {
        log.info("Attempting to stitch mapping {} to symbol {}", mappingId, selectedSymbolId);

        AclfMapping mapping = aclfMappingRepository.findById(mappingId).orElse(null);
        if (mapping == null) {
            log.error("Mapping not found: {}", mappingId);
            return false;
        }

        // 1. Lock the Mapping (Verify)
        mapping.setMappingStrategy("VERIFIED");
        mapping.setConfidenceScore(1.0);

        // If a specific symbol was selected from candidates, update it (Optional based
        // on implementation)
        if (selectedSymbolId != null && !selectedSymbolId.isEmpty()) {
            try {
                mapping.setSourceSymbolId(UUID.fromString(selectedSymbolId));
            } catch (IllegalArgumentException e) {
                log.warn("Invalid symbol UUID provided: {}", selectedSymbolId);
                // Continue anyway to verify the mapping itself
            }
        }

        aclfMappingRepository.save(mapping);

        // 2. Recalculate Project Trust Score
        Project project = mapping.getProject();
        if (project != null) {
            updateProjectMetrics(project);
        }

        log.info("Successfully stitched mapping {} (VERIFIED)", mappingId);
        return true;
    }

    private void updateProjectMetrics(Project project) {
        List<AclfMapping> projectMappings = aclfMappingRepository.findByProject_Name(project.getName());

        if (projectMappings.isEmpty())
            return;

        double totalScore = 0.0;
        int ambiguityCount = 0;
        int mappedRulesCount = 0;

        for (AclfMapping m : projectMappings) {
            mappedRulesCount++;
            if (m.getConfidenceScore() != null) {
                totalScore += m.getConfidenceScore();
            }
            // Count AMBIGUOUS_MATCH as ambiguity
            if ("AMBIGUOUS_MATCH".equals(m.getMappingStrategy())) {
                ambiguityCount++;
            }
        }

        double trustScore = mappedRulesCount > 0 ? (totalScore / mappedRulesCount) : 0.0;

        project.setTotalTrustScore(trustScore);
        project.setAmbiguityCount(ambiguityCount);
        project.setMappedRulesCount(mappedRulesCount);
        project.setLastScoreRefresh(java.time.LocalDateTime.now());

        projectRepository.save(project);
        log.info("Refreshed metrics for project {}: Score={}", project.getName(), trustScore);
    }
}
