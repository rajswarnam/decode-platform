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
            
            // Validate XML structure before attempting to parse
            // XML must start with '<' or have a BOM (Byte Order Mark)
            if (!trimmedContent.startsWith("<")) {
                // Check for BOM (UTF-8 BOM is 0xEF 0xBB 0xBF, which appears as characters)
                boolean hasBom = content.length() >= 3 && 
                    (content.charAt(0) == '\uFEFF' || // UTF-8 BOM
                     (content.charAt(0) == '\uFFFE' && content.length() >= 2)); // UTF-16 BOM
                
                if (!hasBom) {
                    log.error("ACLF file {} does not appear to be valid XML (does not start with '<'). " +
                            "First 100 chars: {}. Skipping XML parsing to avoid WstxUnexpectedCharException.", 
                            file.getName(), trimmedContent.substring(0, Math.min(100, trimmedContent.length())));
                    return tags; // Skip parsing - return empty list
                } else {
                    log.info("ACLF file {} has BOM, attempting to parse after BOM removal", file.getName());
                    // Remove BOM and try again
                    content = content.replaceFirst("^\uFEFF", "").replaceFirst("^\uFFFE", "").trim();
                    if (!content.startsWith("<")) {
                        log.error("ACLF file {} still doesn't start with '<' after BOM removal. Skipping.", file.getName());
                        return tags;
                    }
                }
            }
            
            // Now safe to parse - we've validated it starts with '<'
            JsonNode root = xmlMapper.readTree(content);

            // Navigate to Detail nodes (assuming a standard structure)
            // Note: Structure can vary, so we search for nodes named 'ExternalXML.Detail'
            // or similar
            findAndProcessDetails(root, file, tags);

        } catch (com.fasterxml.jackson.core.JsonParseException e) {
            // This includes WstxUnexpectedCharException (from Woodstox XML parser)
            String errorMsg = e.getMessage();
            if (errorMsg != null && errorMsg.contains("Unexpected character")) {
                log.error("ACLF file {} has invalid XML structure (unexpected character in prolog). " +
                        "This usually means the file is not valid XML or starts with unexpected characters. " +
                        "Error: {}. Skipping this file.", file.getAbsolutePath(), errorMsg);
            } else {
                log.error("Failed to parse ACLF file as XML: {} - {}. File may not be valid XML or may have encoding issues.", 
                        file.getAbsolutePath(), errorMsg);
            }
            // Return empty list to allow other files to be processed
        } catch (com.ctc.wstx.exc.WstxUnexpectedCharException e) {
            // Explicitly catch Woodstox exception (though it should be caught by JsonParseException above)
            log.error("ACLF file {} caused WstxUnexpectedCharException: {}. " +
                    "File does not appear to be valid XML. Skipping.", file.getAbsolutePath(), e.getMessage());
            // Return empty list to allow other files to be processed
        } catch (Exception e) {
            log.error("Failed to parse ACLF file: {} - {}", file.getAbsolutePath(), e.getMessage(), e);
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
}
