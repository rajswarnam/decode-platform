package com.decode.context.orchestrator.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "blueprint_refinements")
public class BlueprintRefinement {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "blueprint_path", nullable = false, length = 500)
    private String blueprintPath;

    @Column(name = "parent_blueprint_path", length = 500)
    private String parentBlueprintPath;

    @Column(name = "project_name", nullable = false, length = 100)
    private String projectName;

    @Column(name = "version", nullable = false)
    private Integer version = 1;

    @Column(name = "refinement_prompt", nullable = false, columnDefinition = "TEXT")
    private String refinementPrompt;

    @Column(name = "code_changes_detected")
    private Boolean codeChangesDetected = false;

    @Column(name = "new_symbols_count")
    private Integer newSymbolsCount = 0;

    @Column(name = "modified_symbols_count")
    private Integer modifiedSymbolsCount = 0;

    @Column(name = "deleted_symbols_count")
    private Integer deletedSymbolsCount = 0;

    @Column(name = "change_summary", columnDefinition = "TEXT")
    private String changeSummary;

    @Column(name = "tokens_used")
    private Integer tokensUsed;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "created_by", length = 100)
    private String createdBy = "system";

    // Constructors
    public BlueprintRefinement() {
    }

    public BlueprintRefinement(String blueprintPath, String parentBlueprintPath, String projectName,
            Integer version, String refinementPrompt) {
        this.blueprintPath = blueprintPath;
        this.parentBlueprintPath = parentBlueprintPath;
        this.projectName = projectName;
        this.version = version;
        this.refinementPrompt = refinementPrompt;
    }

    // Getters and Setters
    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getBlueprintPath() {
        return blueprintPath;
    }

    public void setBlueprintPath(String blueprintPath) {
        this.blueprintPath = blueprintPath;
    }

    public String getParentBlueprintPath() {
        return parentBlueprintPath;
    }

    public void setParentBlueprintPath(String parentBlueprintPath) {
        this.parentBlueprintPath = parentBlueprintPath;
    }

    public String getProjectName() {
        return projectName;
    }

    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getRefinementPrompt() {
        return refinementPrompt;
    }

    public void setRefinementPrompt(String refinementPrompt) {
        this.refinementPrompt = refinementPrompt;
    }

    public Boolean getCodeChangesDetected() {
        return codeChangesDetected;
    }

    public void setCodeChangesDetected(Boolean codeChangesDetected) {
        this.codeChangesDetected = codeChangesDetected;
    }

    public Integer getNewSymbolsCount() {
        return newSymbolsCount;
    }

    public void setNewSymbolsCount(Integer newSymbolsCount) {
        this.newSymbolsCount = newSymbolsCount;
    }

    public Integer getModifiedSymbolsCount() {
        return modifiedSymbolsCount;
    }

    public void setModifiedSymbolsCount(Integer modifiedSymbolsCount) {
        this.modifiedSymbolsCount = modifiedSymbolsCount;
    }

    public Integer getDeletedSymbolsCount() {
        return deletedSymbolsCount;
    }

    public void setDeletedSymbolsCount(Integer deletedSymbolsCount) {
        this.deletedSymbolsCount = deletedSymbolsCount;
    }

    public String getChangeSummary() {
        return changeSummary;
    }

    public void setChangeSummary(String changeSummary) {
        this.changeSummary = changeSummary;
    }

    public Integer getTokensUsed() {
        return tokensUsed;
    }

    public void setTokensUsed(Integer tokensUsed) {
        this.tokensUsed = tokensUsed;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }
}
