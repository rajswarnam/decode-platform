package com.decode.ingestion.engine.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.UUID;
import java.time.LocalDateTime;

@Entity
@Table(name = "source_files")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SourceFile {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    private Project project;

    @Column(name = "file_path", nullable = false)
    private String filePath;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(nullable = false)
    private String extension;

    @Column(name = "content_hash")
    private String contentHash;

    @Column(name = "last_indexed")
    private LocalDateTime lastIndexed;

    @Column(name = "file_summary")
    private String fileSummary;

    @Column(name = "business_context")
    private String businessContext;

    @Column(name = "storage_key")
    private String storageKey;
}
