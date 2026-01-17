package com.decode.code.parser.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ParsedRelationship {
    private String sourceSymbolName;      // Name of the symbol making the call/reference
    private String targetSymbolName;      // Name of the symbol being called/referenced
    private String relationshipType;      // CALLS, POINTER_TO, REFERENCES, PERFORM, CALL_EXTERNAL
    private int sourceLine;               // Line where the relationship occurs
    private int sourceColumn;             // Column where the relationship occurs
    private String context;               // Additional context (e.g., parameters, file name)
}
