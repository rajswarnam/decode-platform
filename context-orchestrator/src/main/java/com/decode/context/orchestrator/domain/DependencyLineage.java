package com.decode.context.orchestrator.domain;

import jakarta.persistence.*;
import lombok.Data;
import java.util.UUID;
import java.time.LocalDateTime;

@Entity
@Table(name = "dependency_lineage")
@Data
public class DependencyLineage {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "source_project_id")
    private Project sourceProject;

    @ManyToOne
    @JoinColumn(name = "source_file_id")
    private SourceFile sourceFile;

    @Column(name = "target_service_name")
    private String targetServiceName;

    private String protocol;

    @Column(name = "connection_type")
    private String connectionType;

    @Column(name = "raw_evidence", columnDefinition = "TEXT")
    private String rawEvidence;

    @Column(name = "confidence_score")
    private Double confidenceScore;

    @Column(name = "discovered_at")
    private LocalDateTime discoveredAt = LocalDateTime.now();
}
