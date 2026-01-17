package com.decode.code.parser.domain;

import jakarta.persistence.*;
import lombok.Data;
import java.util.UUID;

@Entity
@Table(name = "symbol_relationships", 
       uniqueConstraints = @UniqueConstraint(columnNames = {"source_symbol_id", "target_symbol_id", "relationship_type"}))
@Data
public class SymbolRelationship {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "source_symbol_id", nullable = false)
    private Symbol sourceSymbol;

    @ManyToOne
    @JoinColumn(name = "target_symbol_id", nullable = false)
    private Symbol targetSymbol;

    @Column(name = "relationship_type", nullable = false)
    private String relationshipType; // CALLS, POINTER_TO, REFERENCES, PERFORM, CALL_EXTERNAL

    @Column(name = "source_line")
    private int sourceLine;

    @Column(name = "source_column")
    private int sourceColumn;

    @Column(name = "context")
    private String context; // Additional context like parameters, file path where call occurs
}
