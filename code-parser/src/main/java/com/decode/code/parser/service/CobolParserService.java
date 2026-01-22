package com.decode.code.parser.service;

import com.decode.code.parser.dto.ParsedSymbol;
import com.decode.code.parser.dto.ParsedRelationship;
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
import java.util.stream.Collectors;

@Service
@Slf4j
public class CobolParserService implements LanguageParser {

    // Regex for COBOL Data Division fields: Level Name PIC Type [VALUE val]
    private static final Pattern DATA_FIELD_PATTERN = Pattern
            .compile("^\\s*(\\d{2})\\s+([A-Z0-9-]+)\\s+PIC\\s+([^\\.\\s]+)", Pattern.CASE_INSENSITIVE);
    
    // Regex for PERFORM statement: PERFORM paragraph-name [THROUGH/THRU paragraph-name] [TIMES n] [UNTIL condition]
    private static final Pattern PERFORM_PATTERN = Pattern
            .compile("PERFORM\\s+([A-Z0-9-]+)(?:\\s+THROUGH\\s+|\\s+THRU\\s+)?([A-Z0-9-]+)?", Pattern.CASE_INSENSITIVE);
    
    // Regex for CALL statement: CALL 'program-name' [USING identifier...]
    private static final Pattern CALL_PATTERN = Pattern
            .compile("CALL\\s+(?:['\"]?([A-Z0-9-]+)['\"]?|([A-Z0-9-]+))", Pattern.CASE_INSENSITIVE);
    
    // Regex for paragraph/section definition: paragraph-name.
    private static final Pattern PARAGRAPH_PATTERN = Pattern
            .compile("^\\s*([A-Z0-9-]+)\\s*\\.", Pattern.CASE_INSENSITIVE);

    @Override
    public boolean supports(File file) {
        String name = file.getName().toLowerCase();
        
        // Check standard COBOL extensions
        if (name.endsWith(".cbl") || name.endsWith(".cpy") || name.endsWith(".cob")) {
            return true;
        }
        
        // Check .txt files for COBOL content
        if (name.endsWith(".txt")) {
            return isCobolContent(file);
        }
        
        return false;
    }
    
    /**
     * Detects if a .txt file contains COBOL code by checking for COBOL keywords
     */
    private boolean isCobolContent(File file) {
        try {
            // Read first 50 lines to check for COBOL keywords
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            int checkLines = Math.min(50, lines.size());
            
            int cobolKeywordCount = 0;
            boolean hasDataDivision = false;
            boolean hasProcedureDivision = false;
            boolean hasPicStatement = false;
            
            // COBOL keywords that indicate COBOL code
            String[] cobolKeywords = {
                "DATA DIVISION", "PROCEDURE DIVISION", "WORKING-STORAGE", "LINKAGE SECTION",
                "IDENTIFICATION DIVISION", "ENVIRONMENT DIVISION", "PIC", "PICTURE",
                "PERFORM", "CALL", "MOVE", "IF", "ELSE", "END-IF", "EVALUATE",
                "COMPUTE", "ADD", "SUBTRACT", "MULTIPLY", "DIVIDE", "GO TO", "GOBACK",
                "EXIT PROGRAM", "INITIALIZE", "STRING", "UNSTRING", "INSPECT"
            };
            
            for (int i = 0; i < checkLines; i++) {
                String line = lines.get(i).toUpperCase().trim();
                
                // Check for division markers (strong indicators)
                if (line.contains("DATA DIVISION")) {
                    hasDataDivision = true;
                    cobolKeywordCount += 3; // Strong indicator
                }
                if (line.contains("PROCEDURE DIVISION")) {
                    hasProcedureDivision = true;
                    cobolKeywordCount += 3; // Strong indicator
                }
                if (line.contains("IDENTIFICATION DIVISION")) {
                    cobolKeywordCount += 2;
                }
                if (line.contains("ENVIRONMENT DIVISION")) {
                    cobolKeywordCount += 2;
                }
                if (line.contains("WORKING-STORAGE")) {
                    cobolKeywordCount += 2;
                }
                
                // Check for PIC/PICTURE statements (very common in COBOL)
                if (line.matches(".*\\bPIC\\s+[X9S]|.*\\bPICTURE\\s+[X9S]")) {
                    hasPicStatement = true;
                    cobolKeywordCount += 2;
                }
                
                // Check for other COBOL keywords
                for (String keyword : cobolKeywords) {
                    if (line.contains(keyword)) {
                        cobolKeywordCount++;
                    }
                }
            }
            
            // File is likely COBOL if:
            // 1. Has both DATA DIVISION and PROCEDURE DIVISION (strongest indicator)
            // 2. Has DATA DIVISION and PIC statements
            // 3. Has multiple COBOL keywords (threshold: 5+)
            boolean isCobol = (hasDataDivision && hasProcedureDivision) ||
                             (hasDataDivision && hasPicStatement) ||
                             (cobolKeywordCount >= 5);
            
            if (isCobol) {
                log.info("Detected COBOL content in .txt file: {} (keywords found: {}, has DATA DIVISION: {}, has PROCEDURE DIVISION: {})", 
                    file.getName(), cobolKeywordCount, hasDataDivision, hasProcedureDivision);
            }
            
            return isCobol;
            
        } catch (IOException e) {
            log.warn("Error checking COBOL content in .txt file {}: {}", file.getName(), e.getMessage());
            return false; // Fail-safe: don't treat as COBOL if we can't read it
        }
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
            String currentParagraph = null; // Track current paragraph for relationship extraction

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
                    // Extract paragraph/section definitions
                    Matcher paraMatcher = PARAGRAPH_PATTERN.matcher(line);
                    if (paraMatcher.find()) {
                        String paraName = paraMatcher.group(1);
                        currentParagraph = paraName;
                        
                        // Add paragraph as a symbol (callable unit)
                        ParsedSymbol ps = new ParsedSymbol();
                        ps.setName(paraName);
                        ps.setCategory("COBOL_PARAGRAPH");
                        ps.setType("PROCEDURE");
                        ps.setStartLine(i);
                        ps.setEndLine(i);
                        symbols.add(ps);
                    }
                    
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
    
    @Override
    public List<ParsedRelationship> extractRelationships(File file, List<ParsedSymbol> symbols) {
        log.info("Extracting COBOL relationships from: {}", file.getAbsolutePath());
        List<ParsedRelationship> relationships = new ArrayList<>();
        
        try {
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            boolean inProcedureDivision = false;
            String currentParagraph = null; // Track current paragraph making the call
            
            // Build a map of paragraph names for quick lookup
            List<String> paragraphNames = symbols.stream()
                    .filter(s -> "COBOL_PARAGRAPH".equals(s.getCategory()))
                    .map(ParsedSymbol::getName)
                    .collect(Collectors.toList());

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();

                if (line.contains("PROCEDURE DIVISION")) {
                    inProcedureDivision = true;
                }
                
                // Track current paragraph
                Matcher paraMatcher = PARAGRAPH_PATTERN.matcher(line);
                if (paraMatcher.find()) {
                    currentParagraph = paraMatcher.group(1);
                }

                if (inProcedureDivision) {
                    // Extract PERFORM statements (internal calls)
                    Matcher performMatcher = PERFORM_PATTERN.matcher(line);
                    if (performMatcher.find()) {
                        String targetPara = performMatcher.group(1);
                        String throughPara = performMatcher.group(2);
                        
                        if (currentParagraph != null && paragraphNames.contains(targetPara)) {
                            ParsedRelationship rel = new ParsedRelationship(
                                    currentParagraph,
                                    targetPara,
                                    "PERFORM",
                                    i + 1,
                                    0,
                                    throughPara != null ? "THROUGH " + throughPara : ""
                            );
                            relationships.add(rel);
                        }
                    }
                    
                    // Extract CALL statements (external program calls)
                    Matcher callMatcher = CALL_PATTERN.matcher(line);
                    if (callMatcher.find()) {
                        String programName = callMatcher.group(1) != null ? callMatcher.group(1) : callMatcher.group(2);
                        
                        if (currentParagraph != null) {
                            ParsedRelationship rel = new ParsedRelationship(
                                    currentParagraph,
                                    programName,
                                    "CALL_EXTERNAL",
                                    i + 1,
                                    0,
                                    "External program call"
                            );
                            relationships.add(rel);
                        }
                    }
                }
            }

            log.info("Extracted {} COBOL relationships from {}", relationships.size(), file.getName());
            return relationships;
        } catch (IOException e) {
            log.error("Failed to extract relationships from COBOL file", e);
            return relationships;
        }
    }
}
