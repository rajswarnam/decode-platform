package com.decode.code.parser.repository;

import com.decode.code.parser.domain.SymbolRelationship;
import com.decode.code.parser.domain.Symbol;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.UUID;

public interface SymbolRelationshipRepository extends JpaRepository<SymbolRelationship, UUID> {
    
    List<SymbolRelationship> findBySourceSymbol(Symbol sourceSymbol);
    
    List<SymbolRelationship> findByTargetSymbol(Symbol targetSymbol);
    
    List<SymbolRelationship> findByRelationshipType(String relationshipType);
    
    @Query("SELECT sr FROM SymbolRelationship sr WHERE sr.sourceSymbol.sourceFile.project.id = :projectId")
    List<SymbolRelationship> findByProjectId(@Param("projectId") UUID projectId);
    
    @Query("SELECT sr FROM SymbolRelationship sr WHERE sr.sourceSymbol.sourceFile.project.id = :projectId AND sr.relationshipType = :type")
    List<SymbolRelationship> findByProjectIdAndType(@Param("projectId") UUID projectId, @Param("type") String type);
}
