package com.decode.context.orchestrator.service;

import com.decode.context.orchestrator.domain.Project;
import com.decode.context.orchestrator.repository.AclfMappingRepository;
import com.decode.context.orchestrator.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@Slf4j
@RequiredArgsConstructor
public class TrustScoreCalculator {

    private final ProjectRepository projectRepository;
    private final AclfMappingRepository aclfMappingRepository;

    @Transactional
    public void calculateAndStoreProjectTrust(String projectName) {
        Project project = projectRepository.findByName(projectName)
                .orElseThrow(() -> new RuntimeException("Project not found: " + projectName));

        log.info("Calculating Trust Score for project: {}", projectName);

        // 1. Calculate Aggregate Trust Score (Weighted Average)
        Double avgConfidence = aclfMappingRepository.calculateAverageConfidenceByProject(project.getId());

        // 2. Count Ambiguities
        long ambiguityCount = aclfMappingRepository.countByProject_IdAndMappingStrategy(project.getId(),
                "AMBIGUOUS_MATCH");

        // 3. Count Total Mapped Rules
        long totalRules = aclfMappingRepository.countByProject_Id(project.getId());

        // Update Project Entity
        project.setTotalTrustScore(avgConfidence != null ? avgConfidence : 0.0);
        project.setAmbiguityCount((int) ambiguityCount);
        project.setMappedRulesCount((int) totalRules);
        project.setLastScoreRefresh(LocalDateTime.now());

        projectRepository.save(project);

        log.info("Trust Score Metrics Updated: {}% Trust, {} ambiguities found.",
                String.format("%.2f", project.getTotalTrustScore() * 100),
                project.getAmbiguityCount());
    }
}
