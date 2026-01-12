package com.decode.vectorizer.service.domain;

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

    @Column(name = "data_type")
    private String dataType;

    // We construct content: "Domain: Mortgages | Project: LoanSvc | Class:
    // MyClass..."
}
