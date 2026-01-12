package com.decode.context.orchestrator.service;

import com.decode.context.orchestrator.domain.GlobalDictionary;
import com.decode.context.orchestrator.repository.GlobalDictionaryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class ContractMappingService {

    private final GlobalDictionaryRepository dictionaryRepository;
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    @Transactional
    public void parseVendorYaml(File yamlFile, String domain) {
        log.info("Ingesting Vendor YAML Contract: {}", yamlFile.getAbsolutePath());
        try {
            JsonNode rootNode = yamlMapper.readTree(yamlFile);
            processNode(rootNode, "", domain);
        } catch (IOException e) {
            log.error("Failed to parse vendor YAML: {}", e.getMessage());
            throw new RuntimeException("YAML Parsing Error", e);
        }
    }

    private void processNode(JsonNode node, String path, String domain) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String fieldName = field.getKey();
                String newPath = path.isEmpty() ? fieldName : path + "." + fieldName;

                // If it's a leaf node or we want to capture this tag
                createVirtualSymbol(newPath, domain);

                processNode(field.getValue(), newPath, domain);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                processNode(node.get(i), path + "[" + i + "]", domain);
            }
        }
    }

    private void createVirtualSymbol(String technicalName, String domain) {
        // Only create if it doesn't exist
        if (!dictionaryRepository.existsByTechnicalNameAndDomain(technicalName, domain)) {
            GlobalDictionary entry = new GlobalDictionary();
            entry.setTechnicalName(technicalName);
            entry.setDomain(domain);
            entry.setConfidenceScore(0.7); // P3: Semantic/Contract-Based
            entry.setCreatedByAgent(true);
            entry.setDescription("Virtual Symbol extracted from Vendor YAML Contract");

            // Basic inference: camelCase to Standard Label
            entry.setStandardLabel(camelToLabel(technicalName));

            dictionaryRepository.save(entry);
            log.debug("Created Virtual Symbol: {} in domain {}", technicalName, domain);
        }
    }

    private String camelToLabel(String input) {
        if (input == null || input.isEmpty())
            return input;
        StringBuilder result = new StringBuilder();
        String name = input.contains(".") ? input.substring(input.lastIndexOf(".") + 1) : input;
        for (char c : name.toCharArray()) {
            if (Character.isUpperCase(c) && result.length() > 0) {
                result.append(" ");
            }
            result.append(c);
        }
        return result.toString();
    }
}
