package com.decode.ingestion.engine.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.UUID;
import java.util.List;
import java.time.LocalDateTime;

@Entity
@Table(name = "projects")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(unique = true, nullable = false)
    private String name;

    private String domain;

    private String description;

    @Column(name = "base_path", nullable = false)
    private String basePath;

    @Column(name = "git_url")
    private String gitUrl;

    @Column(name = "tech_stack", columnDefinition = "text[]")
    private List<String> techStack;

    private String status = "PENDING"; // PENDING, IN_PROGRESS, COMPLETED, FAILED

    @Column(name = "ingestion_progress")
    private Integer ingestionProgress = 0; // 0-100 percentage

    private String currentFile;

    private Long estimatedRemainingSeconds;

    private Long totalFiles;

    private Long processedFiles;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
