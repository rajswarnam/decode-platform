package com.decode.context.orchestrator.service;

import com.decode.context.orchestrator.domain.AclfMapping;
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
public class StitchingEngine {

    private final AclfMappingRepository aclfMappingRepository;
    private final ProjectRepository projectRepository;

    @Transactional
    public void stitchBlackBoxTransaction(String projectName, String correlationId, String egressRef,
            String ingressRef) {
        log.info("🚀 Initiating Modern Transaction Stitching for CorrelationID: {}", correlationId);

        Project project = projectRepository.findByName(projectName)
                .orElseThrow(() -> new RuntimeException("Project not found: " + projectName));

        // Create Synthetic Lineage entry in aclf_mappings
        AclfMapping syntheticMapping = new AclfMapping();
        syntheticMapping.setProject(project);
        syntheticMapping.setAclfFilePath("VIRTUAL_CONTRACT_" + correlationId);
        syntheticMapping.setAttributeTag("SESSION_CORRELATION");

        // Link the two layers
        String traceContext = String.format("STITCHED: [EGRESS: %s] -> [BLACK_BOX_VENDOR] -> [INGRESS: %s]", egressRef,
                ingressRef);
        syntheticMapping.setExternalLayerRef(traceContext);

        // Trust and Strategy
        syntheticMapping.setConfidenceScore(0.7); // P3: Contract-Based
        syntheticMapping.setMappingStrategy("STITCHED_CONTRACT");

        syntheticMapping.setCreatedAt(LocalDateTime.now());

        aclfMappingRepository.save(syntheticMapping);

        log.info("✅ Synthetic Lineage Created: Transition bridged from {} to {}", egressRef, ingressRef);
    }
}
