package com.decode.context.orchestrator.domain;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "global_dictionary")
@Data
public class GlobalDictionary {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "technical_name", nullable = false)
    private String technicalName;

    @Column(name = "business_name")
    private String businessName;

    @Column(name = "standard_label")
    private String standardLabel;

    private String domain;
    private String description;

    @Column(name = "confidence_score")
    private Double confidenceScore;

    @Column(name = "created_by_agent")
    private Boolean createdByAgent;
}
