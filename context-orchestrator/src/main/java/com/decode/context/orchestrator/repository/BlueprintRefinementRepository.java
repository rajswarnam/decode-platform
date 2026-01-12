package com.decode.context.orchestrator.repository;

import com.decode.context.orchestrator.domain.BlueprintRefinement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BlueprintRefinementRepository extends JpaRepository<BlueprintRefinement, UUID> {

    List<BlueprintRefinement> findByBlueprintPath(String blueprintPath);

    List<BlueprintRefinement> findByParentBlueprintPath(String parentBlueprintPath);

    List<BlueprintRefinement> findByProjectNameOrderByCreatedAtDesc(String projectName);

    List<BlueprintRefinement> findByBlueprintPathOrderByVersionDesc(String blueprintPath);
}
