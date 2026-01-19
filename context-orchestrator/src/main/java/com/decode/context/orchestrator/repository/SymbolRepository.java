package com.decode.context.orchestrator.repository;

import com.decode.context.orchestrator.domain.Symbol;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface SymbolRepository extends JpaRepository<Symbol, UUID> {
    List<Symbol> findTop50ByAnalysisStatus(String analysisStatus);

    List<Symbol> findBySourceFile_Project_Name(String projectName);

    List<Symbol> findTop10ByNameContainingIgnoreCaseAndSourceFile_Project_Name(String name, String projectName);

    long countByAnalysisStatus(String analysisStatus);
}
