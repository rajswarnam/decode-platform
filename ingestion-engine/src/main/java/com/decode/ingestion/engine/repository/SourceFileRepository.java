package com.decode.ingestion.engine.repository;

import com.decode.ingestion.engine.domain.Project;
import com.decode.ingestion.engine.domain.SourceFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SourceFileRepository extends JpaRepository<SourceFile, UUID> {
    Optional<SourceFile> findByProjectAndFilePath(Project project, String filePath);

    long deleteByProjectAndLastIndexedBefore(Project project, LocalDateTime dateTime);
}
