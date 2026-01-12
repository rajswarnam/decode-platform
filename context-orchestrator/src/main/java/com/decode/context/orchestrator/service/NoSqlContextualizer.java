package com.decode.context.orchestrator.service;

import com.decode.context.orchestrator.domain.Symbol;
import com.decode.context.orchestrator.repository.SymbolRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class NoSqlContextualizer {

    private final SymbolRepository symbolRepository;

    @Transactional
    public void inferNoSqlBusinessRules(String projectName) {
        log.info("🔍 Analyzing NoSQL Patterns for project: {}", projectName);

        // Find all Java classes in the project
        List<Symbol> javaClasses = symbolRepository.findBySourceFile_Project_Name(projectName);

        for (Symbol symbol : javaClasses) {
            String metadata = symbol.getMetadata() != null ? symbol.getMetadata().toString() : "";

            // Infer rules from NoSQL Annotations
            if (metadata.contains("@Table") || metadata.contains("@Cassandra")) {
                recordInference(symbol, "CASSANDRA_PERSISTENCE",
                        "System persists business entities in partitioned Cassandra clusters for high-availability.");
            }

            if (metadata.contains("@Document") || metadata.contains("@Elasticsearch")) {
                recordInference(symbol, "ELASTIC_SEARCH_INDEX",
                        "System indexes business entities in Elastic Search for full-text distributed search and analytics.");
            }

            // Infer rules from Repository method names
            if (symbol.getName().endsWith("Repository")) {
                if (symbol.getName().contains("Audit") || symbol.getName().contains("Log")) {
                    recordInference(symbol, "AUDIT_COMPLIANCE",
                            "Automated regulatory audit trail capture for financial transactions.");
                }
            }
        }
    }

    private void recordInference(Symbol symbol, String ruleType, String businessIntent) {
        log.info("✨ Pattern Inferred: [{}]: {}", ruleType, businessIntent);
        // In a real system, we would save this to a 'knowledge_units' table
        // For this mission, we log the extraction success.
    }
}
