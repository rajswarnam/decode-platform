package com.decode.context.orchestrator.domain;

import jakarta.persistence.*;
import lombok.Data;
import java.util.UUID;

@Entity
@Table(name = "symbols")
@Data
public class Symbol {
    @Id
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "file_id")
    private SourceFile sourceFile;

    private String name;
    private String category;

    @Column(name = "analysis_status")
    private String analysisStatus;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private String metadata;
}
