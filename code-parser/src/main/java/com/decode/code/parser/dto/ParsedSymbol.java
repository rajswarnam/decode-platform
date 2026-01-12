package com.decode.code.parser.dto;

import lombok.Data;

@Data
public class ParsedSymbol {
    private String name;
    private String category; // class, method, struct, function
    private String type; // int, void, etc.
    private int startLine;
    private int startColumn;
    private int endLine;
    private int endColumn;
}
