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

    @Override
    public boolean supports(File file) {
        return file.getName().endsWith(".aclf");
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
        java.util.regex.Pattern datalistPattern = java.util.regex.Pattern.compile(
            "ExternalDatalist\\s+(\\w+)\\s*\\{", 
            java.util.regex.Pattern.MULTILINE | java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Matcher datalistMatcher = datalistPattern.matcher(content);
        while (datalistMatcher.find()) {
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
        
        // Pattern 2: Datafield definitions
        // Example: Datafield _ABANUM { Type = DataType.Numeric; Length = 9; }
        java.util.regex.Pattern datafieldPattern = java.util.regex.Pattern.compile(
            "Datafield\\s+(\\w+)\\s*\\{[^}]*Type\\s*=\\s*([^;]+);[^}]*Length\\s*=\\s*(\\d+)", 
            java.util.regex.Pattern.MULTILINE | java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL
        );
        java.util.regex.Matcher datafieldMatcher = datafieldPattern.matcher(content);
        while (datafieldMatcher.find()) {
            String fieldName = datafieldMatcher.group(1);
            String fieldType = datafieldMatcher.group(2).trim();
            String fieldLength = datafieldMatcher.group(3);
            
            ParsedSymbol ps = new ParsedSymbol();
            ps.setName(fieldName);
            ps.setCategory("ACLF_DATAFIELD");
            ps.setType(fieldType + " (Length: " + fieldLength + ")");
            int lineNumber = content.substring(0, datafieldMatcher.start()).split("\n").length;
            ps.setStartLine(lineNumber);
            ps.setEndLine(lineNumber);
            tags.add(ps);
            log.debug("Found Datafield: {} (Type: {}, Length: {})", fieldName, fieldType, fieldLength);
        }
        
        // Pattern 3: Transaction definitions
        // Example: Transaction HSUSAL2 { Execute() { ... } }
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
            
            // Try to extract transaction purpose from comments (if available)
            // Look for comments before transaction definition
            int transactionStart = transactionMatcher.start();
            String beforeTransaction = content.substring(Math.max(0, transactionStart - 500), transactionStart);
            java.util.regex.Pattern commentPattern = java.util.regex.Pattern.compile("//\\s*(.+?)\\n", java.util.regex.Pattern.MULTILINE);
            java.util.regex.Matcher commentMatcher = commentPattern.matcher(beforeTransaction);
            if (commentMatcher.find()) {
                String comment = commentMatcher.group(1).trim();
                if (comment.length() > 0 && comment.length() < 200) {
                    ps.setType("Transaction: " + comment);
                }
            }
        }
        
        log.info("DSL parsing complete for {}. Found {} symbols: {} ExternalDatalists, {} Datafields, {} Transactions", 
                file.getName(), tags.size(),
                tags.stream().filter(t -> t.getCategory().equals("ACLF_EXTERNAL_DATALIST")).count(),
                tags.stream().filter(t -> t.getCategory().equals("ACLF_DATAFIELD")).count(),
                tags.stream().filter(t -> t.getCategory().equals("ACLF_TRANSACTION")).count());
    }
}
