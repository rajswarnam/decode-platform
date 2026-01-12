package com.decode.code.parser.service;

import com.decode.code.parser.dto.ParsedSymbol;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class CobolParserService implements LanguageParser {

    // Regex for COBOL Data Division fields: Level Name PIC Type [VALUE val]
    private static final Pattern DATA_FIELD_PATTERN = Pattern
            .compile("^\\s*(\\d{2})\\s+([A-Z0-9-]+)\\s+PIC\\s+([^\\.\\s]+)", Pattern.CASE_INSENSITIVE);

    @Override
    public boolean supports(File file) {
        String name = file.getName().toLowerCase();
        return name.endsWith(".cbl") || name.endsWith(".cpy") || name.endsWith(".cob");
    }

    @Override
    @Tool(description = "Extracts business knowledge and data symbols from COBOL files.")
    public List<ParsedSymbol> parseFile(File file) {
        log.info("Starting COBOL Knowledge Extraction: {}", file.getAbsolutePath());
        List<ParsedSymbol> symbols = new ArrayList<>();

        try {
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            boolean inDataDivision = false;
            boolean inProcedureDivision = false;
            String currentGroup = "ROOT";

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();

                if (line.contains("DATA DIVISION"))
                    inDataDivision = true;
                if (line.contains("PROCEDURE DIVISION")) {
                    inDataDivision = false;
                    inProcedureDivision = true;
                }

                if (inDataDivision) {
                    Matcher m = DATA_FIELD_PATTERN.matcher(line);
                    if (m.find()) {
                        String level = m.group(1);
                        String name = m.group(2);
                        String type = m.group(3);

                        if ("01".equals(level)) {
                            currentGroup = name;
                        }

                        ParsedSymbol ps = new ParsedSymbol();
                        ps.setName(name);
                        ps.setCategory("COBOL_FIELD");
                        ps.setType(type);
                        ps.setStartLine(i);
                        ps.setEndLine(i);
                        // Store parent struct for disambiguation
                        ps.setCategory("COBOL_FIELD [" + currentGroup + "]");
                        symbols.add(ps);
                    }
                }

                if (inProcedureDivision) {
                    // Simple rule extraction for PoC
                    if (line.startsWith("IF") || line.startsWith("MOVE")) {
                        ParsedSymbol ps = new ParsedSymbol();
                        ps.setName("RULE_" + i);
                        ps.setCategory("BUSINESS_RULE");
                        ps.setType("LOGIC");
                        ps.setStartLine(i);
                        ps.setEndLine(i);
                        symbols.add(ps);
                    }
                }
            }

            return symbols;
        } catch (IOException e) {
            log.error("Failed to read COBOL file", e);
            throw new RuntimeException(e);
        }
    }
}
