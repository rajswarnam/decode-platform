package com.decode.context.orchestrator.repository;

import com.decode.context.orchestrator.domain.SourceFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface SourceFileRepository extends JpaRepository<SourceFile, UUID> {
    java.util.Optional<SourceFile> findFirstByProject_IdAndFileName(UUID projectId, String fileName);
    long countByProject_Id(UUID projectId);
    long countByProject_Domain(String domain);
}
