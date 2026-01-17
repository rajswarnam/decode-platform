package com.decode.code.parser.repository;

import com.decode.code.parser.domain.Symbol;
import com.decode.code.parser.domain.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SymbolRepository extends JpaRepository<Symbol, UUID> {
    Optional<Symbol> findByName(String name);

    List<Symbol> findByNameAndSourceFile_Project(String name, Project project);

    Optional<Symbol> findTopByNameAndSourceFile_ProjectOrderByIdDesc(String name, Project project);
    
    long countBySourceFile_Project_Id(UUID projectId);
}
