package com.decode.code.parser.repository;

import com.decode.code.parser.domain.Symbol;
import com.decode.code.parser.domain.Project;
import com.decode.code.parser.domain.SourceFile;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SymbolRepository extends JpaRepository<Symbol, UUID> {
    Optional<Symbol> findByName(String name);

    List<Symbol> findByNameAndSourceFile_Project(String name, Project project);

    Optional<Symbol> findTopByNameAndSourceFile_ProjectOrderByIdDesc(String name, Project project);
    
    long countBySourceFile_Project_Id(UUID projectId);
    
    long countBySourceFile_Project(Project project);
    
    // Duplicate checking: Find symbols by name, category, sourceFile, and startLine
    // Returns List because there may be duplicates (which is what we're checking for)
    List<Symbol> findByNameAndCategoryAndSourceFileAndStartLine(
        String name, String category, SourceFile sourceFile, int startLine);
}
