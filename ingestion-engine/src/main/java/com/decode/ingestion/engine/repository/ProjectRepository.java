package com.decode.ingestion.engine.repository;

import com.decode.ingestion.engine.domain.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@Repository
public interface ProjectRepository extends JpaRepository<Project, UUID> {
    Optional<Project> findByName(String name);

    @Modifying
    @Query(value = "DELETE FROM aclf_mappings WHERE project_id = :projectId", nativeQuery = true)
    void deleteAclfMappings(@Param("projectId") UUID projectId);
}
