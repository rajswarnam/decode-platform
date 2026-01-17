package com.decode.code.parser.service;

import com.decode.code.parser.dto.ParsedSymbol;
import com.decode.code.parser.dto.ParsedRelationship;
import java.io.File;
import java.util.Collections;
import java.util.List;

public interface LanguageParser {
    List<ParsedSymbol> parseFile(File file);

    boolean supports(File file);
    
    /**
     * Extracts relationships (function calls, pointer dereferences, etc.) from a file.
     * Default implementation returns empty list for parsers that don't support relationship extraction.
     */
    default List<ParsedRelationship> extractRelationships(File file, List<ParsedSymbol> symbols) {
        return Collections.emptyList();
    }
}
