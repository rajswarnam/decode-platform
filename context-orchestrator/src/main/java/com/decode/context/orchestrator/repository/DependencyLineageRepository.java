package com.decode.context.orchestrator.repository;

import com.decode.context.orchestrator.domain.DependencyLineage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DependencyLineageRepository extends JpaRepository<DependencyLineage, UUID> {
    List<DependencyLineage> findBySourceProject_Name(String projectName);
}
