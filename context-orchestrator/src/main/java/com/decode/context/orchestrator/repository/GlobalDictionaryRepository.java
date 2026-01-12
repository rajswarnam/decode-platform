package com.decode.context.orchestrator.repository;

import com.decode.context.orchestrator.domain.GlobalDictionary;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GlobalDictionaryRepository extends JpaRepository<GlobalDictionary, Long> {
    boolean existsByTechnicalNameAndDomain(String technicalName, String domain);

    java.util.Optional<GlobalDictionary> findByTechnicalNameAndDomain(String technicalName, String domain);
}
