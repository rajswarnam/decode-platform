package com.decode.vectorizer.service.repository;

import com.decode.vectorizer.service.domain.Symbol;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface SymbolRepository extends JpaRepository<Symbol, UUID> {
    java.util.List<Symbol> findAllBySourceFile_Project_Id(UUID projectId);
}
