package com.decode.code.parser.service;

import com.decode.code.parser.dto.ParsedSymbol;
import java.io.File;
import java.util.List;

public interface LanguageParser {
    List<ParsedSymbol> parseFile(File file);

    boolean supports(File file);
}
