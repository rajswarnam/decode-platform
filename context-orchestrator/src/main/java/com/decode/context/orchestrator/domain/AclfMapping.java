package com.decode.context.orchestrator.domain;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "aclf_mappings")
@Data
public class AclfMapping {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "project_id")
    private Project project;

    @Column(name = "aclf_file_path")
    private String aclfFilePath;

    @Column(name = "attribute_tag")
    private String attributeTag;

    @Column(name = "source_symbol_id")
    private UUID sourceSymbolId;

    @Column(name = "confidence_score")
    private Double confidenceScore;

    @Column(name = "mapping_strategy")
    private String mappingStrategy;

    @Column(name = "external_layer_ref")
    private String externalLayerRef;

    @Column(name = "raw_data_path")
    private String rawDataPath;

    @Column(name = "zconnect_json_path")
    private String zconnectJsonPath;

    @Column(name = "business_description")
    private String businessDescription;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
