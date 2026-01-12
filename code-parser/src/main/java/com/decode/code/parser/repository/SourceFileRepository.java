package com.decode.code.parser.repository;

import com.decode.code.parser.domain.SourceFile;
import com.decode.code.parser.domain.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
import java.util.Optional;

public interface SourceFileRepository extends JpaRepository<SourceFile, UUID> {
    Optional<SourceFile> findByProjectAndFilePath(Project project, String filePath);

    Optional<SourceFile> findByFilePath(String filePath);
}
