package com.decode.context.orchestrator.service;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class BlueprintService {

    private final JdbcTemplate jdbcTemplate;
    private final MinioClient minioClient;
    private final ChatClient.Builder chatClientBuilder;
    private final TrustScoreCalculator trustScoreCalculator;

    private static final String BUCKET_NAME = "decode-source-code";

    public String generateFusionArgoBlueprint(String projectName) {
        log.info("🚀 Generating Business Logic Blueprint for project: {}", projectName);

        // Trigger Trust Score Update
        trustScoreCalculator.calculateAndStoreProjectTrust(projectName);

        StringBuilder report = new StringBuilder();
        report.append("# Business Logic Blueprint: ").append(projectName).append("\n");
        report.append("**Generated**: ").append(java.time.LocalDateTime.now()).append("\n\n");
        report.append("## 1. Executive Summary\n");
        report.append(
                "This report maps high-level business tags from the ASP/Middleware layer directly to the physical C-Core logic using the Decode.AI Semantic Binding engine.\n\n");
        report.append("| Business Tag | External Layer Ref | C Symbol | Strategy | Functional Summary |\n");
        report.append("| :--- | :--- | :--- | :--- | :--- |\n");

        // 1. Fetch Mappings from DB
        List<Map<String, Object>> mappings = jdbcTemplate.queryForList(
                "SELECT m.business_tag, m.external_layer_ref, m.mapping_strategy, " +
                        "s.name as c_symbol, s.data_type, sf.storage_key, s.start_line, s.end_line " +
                        "FROM aclf_mappings m " +
                        "JOIN symbols s ON m.symbol_id = s.id " +
                        "JOIN source_files sf ON s.file_id = sf.id " +
                        "JOIN projects p ON m.project_id = p.id " +
                        "WHERE p.name = ?",
                projectName);

        log.info("Found {} mappings for blueprinting.", mappings.size());

        for (Map<String, Object> map : mappings) {
            String tag = (String) map.get("business_tag");
            String layerRef = (String) map.get("external_layer_ref");
            String symbol = (String) map.get("c_symbol");
            String strategy = (String) map.get("mapping_strategy");
            String storageKey = (String) map.get("storage_key");
            int start = (int) map.get("start_line");
            int end = (int) map.get("end_line");

            // 2. Fetch Code Snippet from MinIO
            String codeSnippet = fetchCodeSnippet(storageKey, start, end);

            // 3. Summarize via LLM
            String summary = summarizeLogic(tag, symbol, codeSnippet);

            report.append(String.format("| `%s` | %s | `%s` | %s | %s |\n",
                    tag, layerRef != null ? layerRef : "N/A", symbol, strategy, summary));
        }

        return report.toString();
    }

    private String fetchCodeSnippet(String storageKey, int start, int end) {
        try (InputStream stream = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(BUCKET_NAME)
                        .object(storageKey)
                        .build())) {

            List<String> lines = new BufferedReader(new InputStreamReader(stream))
                    .lines().collect(Collectors.toList());

            int actualStart = Math.max(0, start - 5); // Add context
            int actualEnd = Math.min(lines.size(), end + 5);

            return String.join("\n", lines.subList(actualStart, actualEnd));
        } catch (Exception e) {
            log.error("Failed to fetch snippet from MinIO for key: {}", storageKey, e);
            return "Code retrieval failed.";
        }
    }

    private String summarizeLogic(String tag, String symbol, String snippet) {
        String prompt = String.format("""
                The business attribute '%s' maps to the C symbol '%s' in the following code.
                Briefly explain what this code does with the field in a business-friendly way.
                Limit to 15 words.

                CODE:
                %s
                """, tag, symbol, snippet);

        try {
            return chatClientBuilder.build()
                    .prompt(prompt)
                    .call()
                    .content()
                    .replace("\n", " ")
                    .trim();
        } catch (Exception e) {
            return "Failed to generate summary.";
        }
    }
}
