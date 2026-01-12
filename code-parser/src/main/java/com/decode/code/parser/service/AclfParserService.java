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
            String content = Files.readString(file.toPath());
            JsonNode root = xmlMapper.readTree(content);

            // Navigate to Detail nodes (assuming a standard structure)
            // Note: Structure can vary, so we search for nodes named 'ExternalXML.Detail'
            // or similar
            findAndProcessDetails(root, file, tags);

        } catch (Exception e) {
            log.error("Failed to parse ACLF file: {}", file.getAbsolutePath(), e);
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
