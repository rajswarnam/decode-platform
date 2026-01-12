package com.decode.code.parser.domain;

import jakarta.persistence.*;
import lombok.Data;
import java.util.UUID;
import java.time.LocalDateTime;

@Entity
@Table(name = "aclf_mappings")
@Data
public class AclfMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "business_tag", nullable = false)
    private String businessTag;

    @ManyToOne
    @JoinColumn(name = "aclf_file_id")
    private SourceFile aclfFile;

    @ManyToOne
    @JoinColumn(name = "symbol_id")
    private Symbol symbol;

    @Column(name = "c_data_type")
    private String cDataType;

    @Column(name = "memory_offset")
    private Integer memoryOffset;

    @ManyToOne
    @JoinColumn(name = "project_id")
    private Project project;

    @Column(name = "mapping_strategy")
    private String mappingStrategy;

    @Column(name = "confidence_score")
    private Double confidenceScore;

    @Column(name = "raw_config_context", columnDefinition = "TEXT")
    private String rawConfigContext;

    @Column(name = "external_layer_ref")
    private String externalLayerRef;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();
}
