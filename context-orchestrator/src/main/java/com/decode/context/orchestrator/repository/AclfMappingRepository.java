package com.decode.context.orchestrator.repository;

import com.decode.context.orchestrator.domain.AclfMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.UUID;

public interface AclfMappingRepository extends JpaRepository<AclfMapping, UUID> {
    List<AclfMapping> findByProject_Name(String projectName);

    @Query("SELECT AVG(m.confidenceScore) FROM AclfMapping m WHERE m.project.id = :projectId")
    Double calculateAverageConfidenceByProject(@Param("projectId") UUID projectId);

    long countByProject_IdAndMappingStrategy(UUID projectId, String mappingStrategy);

    long countByProject_Id(UUID projectId);
}
