package com.decode.code.parser.domain;

import jakarta.persistence.*;
import lombok.Data;
import java.util.UUID;

@Entity
@Table(name = "symbols")
@Data
public class Symbol {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "file_id")
    private SourceFile sourceFile;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String category;

    @Column(name = "data_type")
    private String dataType;

    @Column(name = "start_line")
    private int startLine;

    @Column(name = "start_column")
    private int startColumn;

    @Column(name = "end_line")
    private int endLine;

    @Column(name = "end_column")
    private int endColumn;
}
