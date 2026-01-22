package com.decode.code.parser.service;

import com.decode.code.parser.domain.AclfMapping;
import com.decode.code.parser.domain.SourceFile;
import com.decode.code.parser.domain.Symbol;
import com.decode.code.parser.dto.ParsedSymbol;
import com.decode.code.parser.repository.AclfMappingRepository;
import com.decode.code.parser.repository.SourceFileRepository;
import com.decode.code.parser.repository.SymbolRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class AclfParserService implements LanguageParser {

    private final AclfMappingRepository aclfMappingRepository;
    private final SymbolRepository symbolRepository;
    private final SourceFileRepository sourceFileRepository;
    private final ChatClient.Builder chatClientBuilder;
    private final XmlMapper xmlMapper = new XmlMapper();
    
    @Value("${parser.aclf.llm-extraction.enabled:true}")
    private boolean llmExtractionEnabled;
    
    @Value("${parser.aclf.llm-extraction.max-file-size:50000}")
    private int maxFileSizeForLLM;

    @Override
    public boolean supports(File file) {
        String fileName = file.getName().toLowerCase();
        boolean supports = fileName.endsWith(".aclf");
        if (!supports && fileName.contains("aclf")) {
            // Log if file name contains "aclf" but doesn't end with .aclf
            log.debug("File {} contains 'aclf' but doesn't end with .aclf", file.getName());
        }
        return supports;
    }

    @Override
    @Transactional
    public List<ParsedSymbol> parseFile(File file) {
        log.info("Starting ACLF Parsing for legacy modernization: {}", file.getName());
        List<ParsedSymbol> tags = new ArrayList<>();

        try {
            // Try multiple encodings for ACLF files
            String content;
            try {
                content = Files.readString(file.toPath(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.nio.charset.MalformedInputException e) {
                log.warn("UTF-8 encoding failed for ACLF file {}, trying ISO-8859-1: {}", file.getName(), e.getMessage());
                try {
                    content = Files.readString(file.toPath(), java.nio.charset.StandardCharsets.ISO_8859_1);
                } catch (java.nio.charset.MalformedInputException e2) {
                    log.warn("ISO-8859-1 encoding also failed, trying Windows-1252: {}", e2.getMessage());
                    content = Files.readString(file.toPath(), java.nio.charset.Charset.forName("Windows-1252"));
                }
            }
            
            // Check if content looks like XML (starts with <) or is empty
            String trimmedContent = content.trim();
            if (trimmedContent.isEmpty()) {
                log.warn("ACLF file {} is empty, skipping", file.getName());
                return tags;
            }
            
            // ACLF files can be in two formats: XML or DSL (Domain-Specific Language)
            // XML format starts with '<', DSL format has keywords like "ExternalDatalist", "Datafield", "Transaction"
            if (trimmedContent.startsWith("<")) {
                // XML format - use existing XML parser
                log.debug("ACLF file {} appears to be XML format", file.getName());
                JsonNode root = xmlMapper.readTree(content);
                // Navigate to Detail nodes (assuming a standard structure)
                // Note: Structure can vary, so we search for nodes named 'ExternalXML.Detail'
                // or similar
                findAndProcessDetails(root, file, tags);
            } else {
                // DSL format - parse using regex/LLM hybrid approach
                log.info("ACLF file {} appears to be DSL format (not XML). Attempting DSL parsing...", file.getName());
                parseDslFormat(content, file, tags);
            }

        } catch (com.fasterxml.jackson.core.JsonParseException e) {
            // This includes WstxUnexpectedCharException (from Woodstox XML parser, wrapped by Jackson)
            String errorMsg = e.getMessage();
            String exceptionType = e.getClass().getSimpleName();
            
            // Check for WstxUnexpectedCharException or similar Woodstox errors
            if (errorMsg != null && (errorMsg.contains("Unexpected character") || 
                                     errorMsg.contains("in prolog") ||
                                     exceptionType.contains("Wstx"))) {
                log.error("ACLF file {} has invalid XML structure (WstxUnexpectedCharException). " +
                        "File does not start with '<' or has unexpected characters in prolog. " +
                        "This usually means the file is not valid XML. Error: {}. Skipping this file.", 
                        file.getAbsolutePath(), errorMsg);
            } else {
                log.error("Failed to parse ACLF file as XML: {} - {}. File may not be valid XML or may have encoding issues.", 
                        file.getAbsolutePath(), errorMsg);
            }
            // Return empty list to allow other files to be processed
        } catch (Exception e) {
            // Catch any other exceptions (including WstxUnexpectedCharException if not wrapped)
            String errorMsg = e.getMessage();
            String exceptionType = e.getClass().getSimpleName();
            
            if (errorMsg != null && (errorMsg.contains("Unexpected character") || 
                                     errorMsg.contains("in prolog") ||
                                     exceptionType.contains("Wstx"))) {
                log.error("ACLF file {} caused WstxUnexpectedCharException: {}. " +
                        "File does not appear to be valid XML (doesn't start with '<'). Skipping.", 
                        file.getAbsolutePath(), errorMsg);
            } else {
                log.error("Failed to parse ACLF file: {} - {}", file.getAbsolutePath(), errorMsg, e);
            }
            // Return empty list to allow other files to be processed
        }

        return tags;
    }

    private void findAndProcessDetails(JsonNode node, File file, List<ParsedSymbol> tags) {
        if (node.isObject()) {
            // Check if this node is a Detail node
            // Assuming the node might be named 'ExternalXML.Detail' or it's an object with
            // Tag/Data attributes
            if (node.has("Tag") && node.has("Data")) {
                processDetailNode(node, file, tags);
            }

            node.fields().forEachRemaining(entry -> findAndProcessDetails(entry.getValue(), file, tags));
        } else if (node.isArray()) {
            node.forEach(child -> findAndProcessDetails(child, file, tags));
        }
    }

    private void processDetailNode(JsonNode node, File file, List<ParsedSymbol> tags) {
        String tag = node.get("Tag").asText();
        String data = node.get("Data").asText();

        log.info("Discovered ACLF Detail: Tag={}, Data={}", tag, data);

        ParsedSymbol ps = new ParsedSymbol();
        ps.setName(tag);
        ps.setCategory("ACLF_TAG");
        ps.setType(data);
        tags.add(ps);

        // Perform Semantic Binding
        bindTagToSymbol(tag, data, node.toString(), file);
    }

    private void bindTagToSymbol(String tag, String data, String rawContext, File file) {
        AclfMapping mapping = new AclfMapping();
        mapping.setBusinessTag(tag);
        mapping.setRawConfigContext(rawContext);

        SourceFile aclfSourceFile = sourceFileRepository.findByFilePath(file.getAbsolutePath()).orElse(null);
        if (aclfSourceFile != null) {
            mapping.setAclfFile(aclfSourceFile);
            mapping.setProject(aclfSourceFile.getProject());
        }

        // Detect Ambiguity: Are there multiple 'age' symbols in this project?
        List<Symbol> candidates = symbolRepository.findByNameAndSourceFile_Project(data, mapping.getProject());

        if (candidates.isEmpty()) {
            log.warn("ACLF Binding: No exact symbol found for {}. Attempting SEMANTIC match...", data);
            performSemanticMapping(mapping, data);
        } else if (candidates.size() > 1) {
            log.info("ACLF Binding AMBIGUOUS: {} matches found for tag '{}'. Logging collision.", candidates.size(),
                    data);
            mapping.setSymbol(candidates.get(0)); // Link to first
            mapping.setMappingStrategy("AMBIGUOUS_MATCH");
            mapping.setConfidenceScore(0.3); // Low confidence due to collision
            mapping.setCDataType(candidates.get(0).getDataType());
            mapping.setRawConfigContext(rawContext + " [AMBIGUITY_LOG: Found " + candidates.size() + " matches]");
        } else {
            log.info("ACLF Binding SUCCESS (EXACT): {} -> {}", tag, data);
            mapping.setSymbol(candidates.get(0));
            mapping.setMappingStrategy("EXACT");
            mapping.setConfidenceScore(1.0);
            mapping.setCDataType(candidates.get(0).getDataType());
        }

        aclfMappingRepository.save(mapping);
    }

    private void performSemanticMapping(AclfMapping mapping, String data) {
        // Prompt LLM to find the best candidate from recently parsed symbols or common
        // patterns
        String prompt = String.format("""
                The legacy ACLF configuration refers to a data target named '%s'.
                This target does not have an exact match in the parsed C symbols.

                Identify the most likely C variable or struct member that this refers to based on naming conventions.
                If unsure, suggest a 'GENERIC' mapping.

                Respond in JSON:
                {
                  "candidate": "actual_symbol_name",
                  "confidence": 0.8,
                  "reasoning": "..."
                }
                """, data);

        try {
            String response = chatClientBuilder.build()
                    .prompt(prompt)
                    .call()
                    .content();

            // For now, we log the intent. In a full implementation, we'd parse the JSON and
            // link to the candidate symbol.
            log.info("LLM Semantic Mapping Proposal for {}: {}", data, response);
            mapping.setMappingStrategy("SEMANTIC");
            mapping.setConfidenceScore(0.5); // Default for semantic until we parse the LLM JSON
        } catch (Exception e) {
            log.error("LLM Semantic Mapping failed", e);
            mapping.setMappingStrategy("FAILED");
            mapping.setConfidenceScore(0.0);
        }
    }

    /**
     * Parse DSL-format ACLF files (non-XML format)
     * DSL format contains: ExternalDatalist, Datafield, Transaction definitions
     */
    private void parseDslFormat(String content, File file, List<ParsedSymbol> tags) {
        log.info("Parsing DSL-format ACLF file: {}", file.getName());
        
        // Pattern 1: ExternalDatalist definitions
        // Example: ExternalDatalist A2AIMGO { ... }
        // More flexible: allow optional whitespace and different brace styles
        java.util.regex.Pattern datalistPattern = java.util.regex.Pattern.compile(
            "ExternalDatalist\\s+(\\w+)\\s*\\{", 
            java.util.regex.Pattern.MULTILINE | java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Matcher datalistMatcher = datalistPattern.matcher(content);
        int datalistMatchCount = 0;
        while (datalistMatcher.find()) {
            datalistMatchCount++;
            String datalistName = datalistMatcher.group(1);
            ParsedSymbol ps = new ParsedSymbol();
            ps.setName(datalistName);
            ps.setCategory("ACLF_EXTERNAL_DATALIST");
            ps.setType("ExternalDatalist");
            // Extract line number
            int lineNumber = content.substring(0, datalistMatcher.start()).split("\n").length;
            ps.setStartLine(lineNumber);
            ps.setEndLine(lineNumber);
            tags.add(ps);
            log.debug("Found ExternalDatalist: {}", datalistName);
        }
        if (datalistMatchCount == 0) {
            log.debug("No ExternalDatalist definitions found in {} (searched for pattern: ExternalDatalist <name> {{)", file.getName());
        }
        
        // Pattern 2: Datafield definitions
        // Example: Datafield _ABANUM { Type = DataType.Numeric; Length = 9; }
        // More flexible: matches Datafield with or without Type/Length in same block
        java.util.regex.Pattern datafieldPattern = java.util.regex.Pattern.compile(
            "Datafield\\s+(\\w+)\\s*\\{", 
            java.util.regex.Pattern.MULTILINE | java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Matcher datafieldMatcher = datafieldPattern.matcher(content);
        while (datafieldMatcher.find()) {
            String fieldName = datafieldMatcher.group(1);
            
            // Find the matching closing brace to extract the full block
            int fieldStart = datafieldMatcher.end();
            int fieldEnd = findMatchingBrace(content, fieldStart);
            String fieldBlock = fieldEnd > fieldStart ? content.substring(fieldStart, fieldEnd) : "";
            
            // Extract Type and Length from the block if present
            String fieldType = "Unknown";
            String fieldLength = "Unknown";
            
            java.util.regex.Pattern typePattern = java.util.regex.Pattern.compile(
                "Type\\s*=\\s*([^;\\n]+)", 
                java.util.regex.Pattern.CASE_INSENSITIVE
            );
            java.util.regex.Matcher typeMatcher = typePattern.matcher(fieldBlock);
            if (typeMatcher.find()) {
                fieldType = typeMatcher.group(1).trim();
            }
            
            java.util.regex.Pattern lengthPattern = java.util.regex.Pattern.compile(
                "Length\\s*=\\s*(\\d+)", 
                java.util.regex.Pattern.CASE_INSENSITIVE
            );
            java.util.regex.Matcher lengthMatcher = lengthPattern.matcher(fieldBlock);
            if (lengthMatcher.find()) {
                fieldLength = lengthMatcher.group(1);
            }
            
            ParsedSymbol ps = new ParsedSymbol();
            ps.setName(fieldName);
            ps.setCategory("ACLF_DATAFIELD");
            ps.setType(fieldType + (fieldLength.equals("Unknown") ? "" : " (Length: " + fieldLength + ")"));
            int lineNumber = content.substring(0, datafieldMatcher.start()).split("\n").length;
            ps.setStartLine(lineNumber);
            ps.setEndLine(lineNumber);
            tags.add(ps);
            log.debug("Found Datafield: {} (Type: {}, Length: {})", fieldName, fieldType, fieldLength);
        }
        if (tags.stream().filter(t -> t.getCategory().equals("ACLF_DATAFIELD")).count() == 0) {
            log.debug("No Datafield definitions found in {} (searched for pattern: Datafield <name> {{)", file.getName());
        }
        
        // Pattern 3: Transaction definitions
        // Example: Transaction VKWFLOSA { MajorFunction = ...; SubFunction = ...; Execute() { ... } }
        java.util.regex.Pattern transactionPattern = java.util.regex.Pattern.compile(
            "Transaction\\s+(\\w+)\\s*\\{", 
            java.util.regex.Pattern.MULTILINE | java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Matcher transactionMatcher = transactionPattern.matcher(content);
        while (transactionMatcher.find()) {
            String transactionName = transactionMatcher.group(1);
            ParsedSymbol ps = new ParsedSymbol();
            ps.setName(transactionName);
            ps.setCategory("ACLF_TRANSACTION");
            ps.setType("Transaction");
            int lineNumber = content.substring(0, transactionMatcher.start()).split("\n").length;
            ps.setStartLine(lineNumber);
            ps.setEndLine(lineNumber);
            tags.add(ps);
            log.debug("Found Transaction: {}", transactionName);
            
            // Extract transaction properties (MajorFunction, SubFunction, NextTransaction, etc.)
            // Find the transaction block content
            int transactionStart = transactionMatcher.end();
            int transactionEnd = findMatchingBrace(content, transactionStart);
            if (transactionEnd > transactionStart) {
                String transactionBlock = content.substring(transactionStart, transactionEnd);
                
                // Extract MajorFunction
                java.util.regex.Pattern majorFunctionPattern = java.util.regex.Pattern.compile(
                    "MajorFunction\\s*=\\s*([^;]+);", 
                    java.util.regex.Pattern.CASE_INSENSITIVE
                );
                java.util.regex.Matcher majorFunctionMatcher = majorFunctionPattern.matcher(transactionBlock);
                if (majorFunctionMatcher.find()) {
                    String majorFunction = majorFunctionMatcher.group(1).trim();
                    ps.setType("Transaction (MajorFunction: " + majorFunction + ")");
                }
                
                // Extract SubFunction
                java.util.regex.Pattern subFunctionPattern = java.util.regex.Pattern.compile(
                    "SubFunction\\s*=\\s*([^;]+);", 
                    java.util.regex.Pattern.CASE_INSENSITIVE
                );
                java.util.regex.Matcher subFunctionMatcher = subFunctionPattern.matcher(transactionBlock);
                if (subFunctionMatcher.find()) {
                    String subFunction = subFunctionMatcher.group(1).trim();
                    String currentType = ps.getType();
                    ps.setType(currentType + ", SubFunction: " + subFunction);
                }
                
                // Extract NextTransaction
                java.util.regex.Pattern nextTransactionPattern = java.util.regex.Pattern.compile(
                    "NextTransaction\\s*=\\s*Transaction\\.(\\w+);", 
                    java.util.regex.Pattern.CASE_INSENSITIVE
                );
                java.util.regex.Matcher nextTransactionMatcher = nextTransactionPattern.matcher(transactionBlock);
                if (nextTransactionMatcher.find()) {
                    String nextTransaction = nextTransactionMatcher.group(1);
                    // Add as a separate symbol reference
                    ParsedSymbol nextPs = new ParsedSymbol();
                    nextPs.setName(nextTransaction);
                    nextPs.setCategory("ACLF_TRANSACTION_REFERENCE");
                    nextPs.setType("NextTransaction from " + transactionName);
                    int nextLineNumber = content.substring(0, transactionStart + nextTransactionMatcher.start()).split("\n").length;
                    nextPs.setStartLine(nextLineNumber);
                    nextPs.setEndLine(nextLineNumber);
                    tags.add(nextPs);
                    log.debug("Found NextTransaction reference: {} from {}", nextTransaction, transactionName);
                }
            }
            
            // Try to extract transaction purpose from comments (if available)
            // Look for comments before transaction definition
            int transactionStartForComments = transactionMatcher.start();
            String beforeTransaction = content.substring(Math.max(0, transactionStartForComments - 500), transactionStartForComments);
            java.util.regex.Pattern commentPattern = java.util.regex.Pattern.compile("//\\s*(.+?)\\n", java.util.regex.Pattern.MULTILINE);
            java.util.regex.Matcher commentMatcher = commentPattern.matcher(beforeTransaction);
            if (commentMatcher.find()) {
                String comment = commentMatcher.group(1).trim();
                if (comment.length() > 0 && comment.length() < 200) {
                    String currentType = ps.getType();
                    if (currentType.equals("Transaction")) {
                        ps.setType("Transaction: " + comment);
                    } else {
                        ps.setType(currentType + " (" + comment + ")");
                    }
                }
            }
        }
        
        // Pattern 3b: Extract Transaction references from PerformTransaction calls
        // Example: PerformTransaction(Transaction: Transaction.BKWFLOS);
        // Example: PerformTransaction(Transaction: Transaction.VKWFLOSA);
        java.util.regex.Pattern performTransactionPattern = java.util.regex.Pattern.compile(
            "PerformTransaction\\s*\\([^)]*Transaction\\s*:\\s*Transaction\\.(\\w+)", 
            java.util.regex.Pattern.MULTILINE | java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Matcher performTransactionMatcher = performTransactionPattern.matcher(content);
        java.util.Set<String> transactionReferences = new java.util.HashSet<>();
        while (performTransactionMatcher.find()) {
            String referencedTransaction = performTransactionMatcher.group(1);
            
            // Avoid duplicates
            if (!transactionReferences.contains(referencedTransaction)) {
                transactionReferences.add(referencedTransaction);
                
                ParsedSymbol ps = new ParsedSymbol();
                ps.setName(referencedTransaction);
                ps.setCategory("ACLF_TRANSACTION_REFERENCE");
                ps.setType("Transaction referenced in PerformTransaction call");
                int lineNumber = content.substring(0, performTransactionMatcher.start()).split("\n").length;
                ps.setStartLine(lineNumber);
                ps.setEndLine(lineNumber);
                tags.add(ps);
                log.debug("Found Transaction reference in PerformTransaction: {}", referencedTransaction);
            }
        }
        
        // Pattern 3c: Extract Transaction references from other contexts
        // Example: Transaction.BKWFLOS (standalone reference)
        // Example: if (Transaction.BKWFLOS == ...)
        java.util.regex.Pattern transactionRefPattern = java.util.regex.Pattern.compile(
            "Transaction\\.(\\w+)", 
            java.util.regex.Pattern.MULTILINE | java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Matcher transactionRefMatcher = transactionRefPattern.matcher(content);
        while (transactionRefMatcher.find()) {
            String referencedTransaction = transactionRefMatcher.group(1);
            
            // Skip if already found in PerformTransaction pattern
            if (!transactionReferences.contains(referencedTransaction)) {
                transactionReferences.add(referencedTransaction);
                
                ParsedSymbol ps = new ParsedSymbol();
                ps.setName(referencedTransaction);
                ps.setCategory("ACLF_TRANSACTION_REFERENCE");
                ps.setType("Transaction reference");
                int lineNumber = content.substring(0, transactionRefMatcher.start()).split("\n").length;
                ps.setStartLine(lineNumber);
                ps.setEndLine(lineNumber);
                tags.add(ps);
                log.debug("Found Transaction reference: {}", referencedTransaction);
            }
        }
        
        // Pattern 4: FormBlock definitions
        // Example: FormBlock HSACCTB1 { Height = 10; Width = 80; Fields = { ... } }
        java.util.regex.Pattern formBlockPattern = java.util.regex.Pattern.compile(
            "FormBlock\\s+(\\w+)\\s*\\{", 
            java.util.regex.Pattern.MULTILINE | java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Matcher formBlockMatcher = formBlockPattern.matcher(content);
        while (formBlockMatcher.find()) {
            String formBlockName = formBlockMatcher.group(1);
            ParsedSymbol ps = new ParsedSymbol();
            ps.setName(formBlockName);
            ps.setCategory("ACLF_FORM_BLOCK");
            ps.setType("FormBlock");
            int lineNumber = content.substring(0, formBlockMatcher.start()).split("\n").length;
            ps.setStartLine(lineNumber);
            ps.setEndLine(lineNumber);
            tags.add(ps);
            log.debug("Found FormBlock: {}", formBlockName);
        }
        
        // Pattern 5: FormReport definitions
        // Example: FormReport HSCUSTF { Description = "Host Customer List"; ... }
        java.util.regex.Pattern formReportPattern = java.util.regex.Pattern.compile(
            "FormReport\\s+(\\w+)\\s*\\{[^}]*Description\\s*=\\s*\"([^\"]+)\"", 
            java.util.regex.Pattern.MULTILINE | java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL
        );
        java.util.regex.Matcher formReportMatcher = formReportPattern.matcher(content);
        while (formReportMatcher.find()) {
            String formReportName = formReportMatcher.group(1);
            String description = formReportMatcher.group(2);
            ParsedSymbol ps = new ParsedSymbol();
            ps.setName(formReportName);
            ps.setCategory("ACLF_FORM_REPORT");
            ps.setType("FormReport: " + description);
            int lineNumber = content.substring(0, formReportMatcher.start()).split("\n").length;
            ps.setStartLine(lineNumber);
            ps.setEndLine(lineNumber);
            tags.add(ps);
            log.debug("Found FormReport: {} ({})", formReportName, description);
        }
        
        // Pattern 6: Calculation definitions
        // Example: Calculation CALCDAYS { Execute() { ... } }
        java.util.regex.Pattern calculationPattern = java.util.regex.Pattern.compile(
            "Calculation\\s+(\\w+)\\s*\\{", 
            java.util.regex.Pattern.MULTILINE | java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Matcher calculationMatcher = calculationPattern.matcher(content);
        while (calculationMatcher.find()) {
            String calculationName = calculationMatcher.group(1);
            ParsedSymbol ps = new ParsedSymbol();
            ps.setName(calculationName);
            ps.setCategory("ACLF_CALCULATION");
            ps.setType("Calculation");
            int lineNumber = content.substring(0, calculationMatcher.start()).split("\n").length;
            ps.setStartLine(lineNumber);
            ps.setEndLine(lineNumber);
            tags.add(ps);
            log.debug("Found Calculation: {}", calculationName);
            
            // Try to extract calculation purpose from comments within the calculation block
            int calculationStart = calculationMatcher.start();
            int calculationEnd = Math.min(content.length(), calculationStart + 1000); // Look at first 1000 chars
            String calculationBlock = content.substring(calculationStart, calculationEnd);
            java.util.regex.Pattern commentPattern = java.util.regex.Pattern.compile("//\\s*(.+?)\\n", java.util.regex.Pattern.MULTILINE);
            java.util.regex.Matcher commentMatcher = commentPattern.matcher(calculationBlock);
            if (commentMatcher.find()) {
                String comment = commentMatcher.group(1).trim();
                if (comment.length() > 0 && comment.length() < 200) {
                    ps.setType("Calculation: " + comment);
                }
            }
        }
        
        // Pattern 7: Extract field references from Data assignments
        // Example: Data = FQDF.HSBKYCM.BCUSTID[1]; or Data = FQDF.BPBKYC.BCUSTID[1];
        // These are field references within ExternalDatalist/Transaction blocks
        java.util.regex.Pattern fieldReferencePattern = java.util.regex.Pattern.compile(
            "Data\\s*=\\s*FQDF\\.(\\w+)\\.(\\w+)\\[\\d+\\]", 
            java.util.regex.Pattern.MULTILINE | java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Matcher fieldReferenceMatcher = fieldReferencePattern.matcher(content);
        java.util.Set<String> uniqueFieldReferences = new java.util.HashSet<>(); // Track unique field names to avoid duplicates
        while (fieldReferenceMatcher.find()) {
            String datalistName = fieldReferenceMatcher.group(1); // e.g., HSBKYCM
            String fieldName = fieldReferenceMatcher.group(2); // e.g., BCUSTID
            
            // Create a unique key to avoid duplicate symbols for the same field in same datalist
            String uniqueKey = datalistName + "." + fieldName;
            if (!uniqueFieldReferences.contains(uniqueKey)) {
                uniqueFieldReferences.add(uniqueKey);
                
                ParsedSymbol ps = new ParsedSymbol();
                ps.setName(fieldName);
                ps.setCategory("ACLF_FIELD_REFERENCE");
                ps.setType("Field in " + datalistName);
                int lineNumber = content.substring(0, fieldReferenceMatcher.start()).split("\n").length;
                ps.setStartLine(lineNumber);
                ps.setEndLine(lineNumber);
                tags.add(ps);
                log.debug("Found field reference: {} in {}", fieldName, datalistName);
            }
        }
        
        // Pattern 8: Extract FQDF field references from code (assignments, expressions, function calls)
        // Example: FQDF.LOCAL.NUM10 = FQDF.LOCAL.NUM10 + FQDF.LOCAL.TZOFFSET;
        // Example: FQDF.SYSTEM.CONDCODE, FQDF.MESSAGE.MSGPREFX
        // This captures field references in Calculation/Transaction Execute() blocks
        java.util.regex.Pattern fqdfFieldPattern = java.util.regex.Pattern.compile(
            "FQDF\\.(\\w+)\\.(\\w+)(?:\\[\\d+\\])?", 
            java.util.regex.Pattern.MULTILINE | java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Matcher fqdfFieldMatcher = fqdfFieldPattern.matcher(content);
        while (fqdfFieldMatcher.find()) {
            String containerName = fqdfFieldMatcher.group(1); // e.g., LOCAL, SYSTEM, MESSAGE
            String fieldName = fqdfFieldMatcher.group(2); // e.g., NUM10, TZOFFSET, CONDCODE
            
            // Create a unique key to avoid duplicate symbols
            String uniqueKey = containerName + "." + fieldName;
            if (!uniqueFieldReferences.contains(uniqueKey)) {
                uniqueFieldReferences.add(uniqueKey);
                
                ParsedSymbol ps = new ParsedSymbol();
                ps.setName(fieldName);
                ps.setCategory("ACLF_FIELD_REFERENCE");
                ps.setType("FQDF field in " + containerName);
                int lineNumber = content.substring(0, fqdfFieldMatcher.start()).split("\n").length;
                ps.setStartLine(lineNumber);
                ps.setEndLine(lineNumber);
                tags.add(ps);
                log.debug("Found FQDF field reference: {} in {}", fieldName, containerName);
            }
        }
        
        // Pattern 9: Extract Datafield names from Datalist Entries
        // Example: { Name = Datafield, [_BCMOPID]; Occurrences = 1; }
        // Example: { Name = Datafield, [_BCMWSID]; Occurrences = 1; }
        java.util.regex.Pattern datalistEntryPattern = java.util.regex.Pattern.compile(
            "Name\\s*=\\s*Datafield\\s*,\\s*\\[(\\w+)\\]", 
            java.util.regex.Pattern.MULTILINE | java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Matcher datalistEntryMatcher = datalistEntryPattern.matcher(content);
        while (datalistEntryMatcher.find()) {
            String datafieldName = datalistEntryMatcher.group(1); // e.g., _BCMOPID, _BCMWSID
            
            // Create a unique key
            String uniqueKey = "DATALIST_ENTRY." + datafieldName;
            if (!uniqueFieldReferences.contains(uniqueKey)) {
                uniqueFieldReferences.add(uniqueKey);
                
                ParsedSymbol ps = new ParsedSymbol();
                ps.setName(datafieldName);
                ps.setCategory("ACLF_DATAFIELD_REFERENCE");
                ps.setType("Datafield in Datalist Entry");
                int lineNumber = content.substring(0, datalistEntryMatcher.start()).split("\n").length;
                ps.setStartLine(lineNumber);
                ps.setEndLine(lineNumber);
                tags.add(ps);
                log.debug("Found Datafield in Datalist Entry: {}", datafieldName);
            }
        }
        
        // Pattern 10: Extract field references from function parameters
        // Example: MoveToField(Source: FQDF.SYSTEM.COMPCODE, Target: FQDF.MESSAGE.MSGSUFFX)
        // Example: ListAddItem(Reference: FQDF.FILLDDLB.REFDDLB, Value: FQDF._HOLPROF._HOLPFIO)
        java.util.regex.Pattern functionParamPattern = java.util.regex.Pattern.compile(
            "(?:Source|Target|Reference|Value|Enabler|Table|Index)\\s*:\\s*FQDF\\.(\\w+)\\.(\\w+)(?:\\[\\d+\\])?", 
            java.util.regex.Pattern.MULTILINE | java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Matcher functionParamMatcher = functionParamPattern.matcher(content);
        while (functionParamMatcher.find()) {
            String containerName = functionParamMatcher.group(1);
            String fieldName = functionParamMatcher.group(2);
            
            String uniqueKey = containerName + "." + fieldName;
            if (!uniqueFieldReferences.contains(uniqueKey)) {
                uniqueFieldReferences.add(uniqueKey);
                
                ParsedSymbol ps = new ParsedSymbol();
                ps.setName(fieldName);
                ps.setCategory("ACLF_FIELD_REFERENCE");
                ps.setType("Field in " + containerName + " (function parameter)");
                int lineNumber = content.substring(0, functionParamMatcher.start()).split("\n").length;
                ps.setStartLine(lineNumber);
                ps.setEndLine(lineNumber);
                tags.add(ps);
                log.debug("Found field reference in function parameter: {} in {}", fieldName, containerName);
            }
        }
        
        long datalistCount = tags.stream().filter(t -> t.getCategory().equals("ACLF_EXTERNAL_DATALIST")).count();
        long datafieldCount = tags.stream().filter(t -> t.getCategory().equals("ACLF_DATAFIELD")).count();
        long transactionCount = tags.stream().filter(t -> t.getCategory().equals("ACLF_TRANSACTION")).count();
        long transactionRefCount = tags.stream().filter(t -> t.getCategory().equals("ACLF_TRANSACTION_REFERENCE")).count();
        long formBlockCount = tags.stream().filter(t -> t.getCategory().equals("ACLF_FORM_BLOCK")).count();
        long formReportCount = tags.stream().filter(t -> t.getCategory().equals("ACLF_FORM_REPORT")).count();
        long calculationCount = tags.stream().filter(t -> t.getCategory().equals("ACLF_CALCULATION")).count();
        long fieldReferenceCount = tags.stream().filter(t -> t.getCategory().equals("ACLF_FIELD_REFERENCE")).count();
        long datafieldReferenceCount = tags.stream().filter(t -> t.getCategory().equals("ACLF_DATAFIELD_REFERENCE")).count();
        
        log.info("DSL parsing complete (regex patterns) for {}. Found {} symbols: {} ExternalDatalists, {} Datafields, {} Transactions, {} TransactionReferences, {} FormBlocks, {} FormReports, {} Calculations, {} FieldReferences, {} DatafieldReferences", 
                file.getName(), tags.size(), datalistCount, datafieldCount, transactionCount, transactionRefCount, formBlockCount, formReportCount, calculationCount, fieldReferenceCount, datafieldReferenceCount);
        
        // HYBRID APPROACH: Use LLM to extract additional field references from unknown patterns
        // This catches patterns that regex might miss (nested structures, complex expressions, etc.)
        if (llmExtractionEnabled) {
            try {
                List<ParsedSymbol> llmExtractedFields = extractFieldsWithLLM(content, file, uniqueFieldReferences);
                if (!llmExtractedFields.isEmpty()) {
                    tags.addAll(llmExtractedFields);
                    log.info("LLM extraction found {} additional field references in {}", llmExtractedFields.size(), file.getName());
                }
            } catch (Exception e) {
                log.warn("LLM-based field extraction failed for {}: {}. Continuing with regex-only results.", file.getName(), e.getMessage());
            }
        } else {
            log.debug("LLM extraction is disabled. Using regex patterns only.");
        }
        
        // Final summary
        long totalFieldReferences = tags.stream().filter(t -> t.getCategory().equals("ACLF_FIELD_REFERENCE")).count();
        long totalDatafieldReferences = tags.stream().filter(t -> t.getCategory().equals("ACLF_DATAFIELD_REFERENCE")).count();
        log.info("Final parsing summary for {}: {} total symbols ({} FieldReferences, {} DatafieldReferences)", 
                file.getName(), tags.size(), totalFieldReferences, totalDatafieldReferences);
    }
    
    /**
     * Helper method to find the matching closing brace for a given opening brace position
     * Used to extract content within Transaction/Calculation/FormBlock blocks
     */
    private int findMatchingBrace(String content, int startPos) {
        if (startPos >= content.length()) return -1;
        
        int depth = 0;
        for (int i = startPos; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1; // No matching brace found
    }
    
    /**
     * Use LLM to extract field references from unknown/complex patterns
     * This complements regex patterns by catching patterns we haven't seen before
     */
    private List<ParsedSymbol> extractFieldsWithLLM(String content, File file, java.util.Set<String> alreadyExtracted) {
        List<ParsedSymbol> llmFields = new ArrayList<>();
        
        // Only use LLM for files that are reasonably sized (avoid token limits)
        if (content.length() > maxFileSizeForLLM) {
            log.debug("Skipping LLM extraction for large file {} ({} chars, max: {})", file.getName(), content.length(), maxFileSizeForLLM);
            return llmFields;
        }
        
        // Sample a representative portion if file is large
        String contentSample = content;
        if (content.length() > 20000) {
            // Take first 10k and last 10k chars to get structure and examples
            int sampleSize = 10000;
            contentSample = content.substring(0, Math.min(sampleSize, content.length())) + 
                          "\n\n... [middle section truncated] ...\n\n" +
                          content.substring(Math.max(0, content.length() - sampleSize));
        }
        
        String prompt = String.format("""
            You are analyzing an ACLF (Application Configuration Language File) to extract field references and data definitions.
            
            The file content (sample):
            %s
            
            Extract ALL field references, datafield names, and identifiers that represent data elements.
            Look for:
            1. Field references in any format (FQDF.XXX.YYY, XXX.YYY, standalone identifiers)
            2. Datafield definitions in any structure (not just the patterns we know)
            3. Variable names, field names, identifiers used in assignments, expressions, function calls
            4. Any other data elements that should be tracked
            
            Return a JSON array of extracted fields. For each field, provide:
            {
              "name": "field_name",
              "category": "ACLF_FIELD_REFERENCE" or "ACLF_DATAFIELD_REFERENCE",
              "type": "brief description of context",
              "line": approximate line number if visible
            }
            
            Focus on extracting fields that might not be caught by standard regex patterns.
            Return ONLY valid JSON array, no other text.
            """, contentSample);
        
        String response = null;
        try {
            response = chatClientBuilder.build()
                    .prompt(prompt)
                    .call()
                    .content();
            
            // Validate response before parsing
            if (response == null || response.trim().isEmpty()) {
                log.warn("LLM returned empty response for {}", file.getName());
                return llmFields;
            }
            
            // Check if response looks like an error message
            String trimmedResponse = response.trim();
            if (trimmedResponse.startsWith("Error") || 
                trimmedResponse.startsWith("error") ||
                trimmedResponse.startsWith("ERROR") ||
                trimmedResponse.toLowerCase().contains("i cannot") ||
                trimmedResponse.toLowerCase().contains("i'm sorry") ||
                trimmedResponse.toLowerCase().contains("unable to")) {
                log.warn("LLM returned error response for {}: {}", file.getName(), 
                        trimmedResponse.length() > 200 ? trimmedResponse.substring(0, 200) + "..." : trimmedResponse);
                return llmFields;
            }
            
            // Try to extract JSON from response if it's wrapped in markdown code blocks
            String jsonContent = trimmedResponse;
            if (trimmedResponse.startsWith("```json")) {
                int startIdx = trimmedResponse.indexOf("```json") + 7;
                int endIdx = trimmedResponse.indexOf("```", startIdx);
                if (endIdx > startIdx) {
                    jsonContent = trimmedResponse.substring(startIdx, endIdx).trim();
                }
            } else if (trimmedResponse.startsWith("```")) {
                int startIdx = trimmedResponse.indexOf("```") + 3;
                int endIdx = trimmedResponse.indexOf("```", startIdx);
                if (endIdx > startIdx) {
                    jsonContent = trimmedResponse.substring(startIdx, endIdx).trim();
                }
            }
            
            // Parse LLM response (JSON array)
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode jsonArray = mapper.readTree(jsonContent);
            
            if (jsonArray.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode fieldNode : jsonArray) {
                    String fieldName = fieldNode.has("name") ? fieldNode.get("name").asText() : null;
                    String category = fieldNode.has("category") ? fieldNode.get("category").asText() : "ACLF_FIELD_REFERENCE";
                    String type = fieldNode.has("type") ? fieldNode.get("type").asText() : "LLM-extracted";
                    int line = fieldNode.has("line") ? fieldNode.get("line").asInt() : 1;
                    
                    if (fieldName != null && !fieldName.trim().isEmpty()) {
                        // Check if we already extracted this field
                        String uniqueKey = category + "." + fieldName;
                        if (!alreadyExtracted.contains(uniqueKey)) {
                            alreadyExtracted.add(uniqueKey);
                            
                            ParsedSymbol ps = new ParsedSymbol();
                            ps.setName(fieldName.trim());
                            ps.setCategory(category);
                            ps.setType(type);
                            ps.setStartLine(line);
                            ps.setEndLine(line);
                            llmFields.add(ps);
                            log.debug("LLM extracted field: {} (category: {})", fieldName, category);
                        }
                    }
                }
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("Failed to parse LLM response as JSON for {}: {}. Raw response (first 500 chars): {}", 
                    file.getName(), e.getMessage(), 
                    response != null && response.length() > 500 ? response.substring(0, 500) + "..." : response);
        } catch (Exception e) {
            log.warn("LLM field extraction failed for {}: {}. Raw response (first 500 chars): {}", 
                    file.getName(), e.getMessage(),
                    response != null && response.length() > 500 ? response.substring(0, 500) + "..." : response);
        }
        
        return llmFields;
    }
}
