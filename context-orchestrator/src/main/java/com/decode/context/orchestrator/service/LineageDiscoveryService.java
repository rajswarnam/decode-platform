package com.decode.context.orchestrator.service;

import com.decode.context.orchestrator.domain.DependencyLineage;
import com.decode.context.orchestrator.domain.SourceFile;
import com.decode.context.orchestrator.domain.Symbol;
import com.decode.context.orchestrator.repository.DependencyLineageRepository;
import com.decode.context.orchestrator.repository.SymbolRepository;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class LineageDiscoveryService {

    private final VectorStore vectorStore;
    private final ChatClient.Builder chatClientBuilder;
    private final MinioClient minioClient;
    private final SymbolRepository symbolRepository;
    private final DependencyLineageRepository lineageRepository;

    @Value("${minio.bucket}")
    private String bucket;

    public int discoverLineage() {
        log.info("Starting Semantic Lineage Discovery Sweep...");

        // Search for patterns indicating outbound calls across languages
        String discoveryQuery = "outgoing HTTP request, API call, client connection, FeignClient, fetch call, axios request, WebClient, requests.get";

        var searchRequestBuilder = SearchRequest.builder()
                .query(discoveryQuery)
                .topK(20);

        List<Document> documents = vectorStore.similaritySearch(searchRequestBuilder.build());
        log.info("Semantic sweep found {} candidate symbols for lineage analysis", documents.size());

        int discoveredCount = 0;
        for (Document doc : documents) {
            String symbolIdStr = (String) doc.getMetadata().get("symbol_id");
            if (symbolIdStr == null)
                continue;

            try {
                UUID symbolId = UUID.fromString(symbolIdStr);
                Symbol symbol = symbolRepository.findById(symbolId).orElse(null);

                if (symbol != null && symbol.getSourceFile() != null) {
                    DependencyLineage lineage = analyzeSymbolForLineage(symbol);
                    if (lineage != null) {
                        lineageRepository.save(lineage);
                        discoveredCount++;
                    }
                }
            } catch (Exception e) {
                log.error("Error analyzing lineage for symbol: {}", symbolIdStr, e);
            }
        }

        log.info("Lineage discovery complete. Identified {} hidden dependencies.", discoveredCount);
        return discoveredCount;
    }

    private DependencyLineage analyzeSymbolForLineage(Symbol symbol) {
        SourceFile file = symbol.getSourceFile();
        String sourceCode = fetchSourceFromMinio(file.getStorageKey());

        String analysisPrompt = String.format(
                """
                        Analyze the following source code and determine if it makes an external network call (HTTP, TCP, gRPC, etc.).

                        Source Code:
                        %s

                        If you find an external call, respond in exactly this format:
                        TARGET: [Target Service Name or URL]
                        PROTOCOL: [e.g. HTTP, SQL, Feign]
                        TYPE: [e.g. Sync, Async]
                        EVIDENCE: [Brief description of the code snippet]
                        CONFIDENCE: [0.0 to 1.0]

                        If NO external call is found, respond with "NONE".
                        """,
                sourceCode);

        String response = chatClientBuilder.build()
                .prompt(analysisPrompt)
                .call()
                .content();

        if (response == null || response.contains("NONE")) {
            return null;
        }

        try {
            DependencyLineage lineage = new DependencyLineage();
            lineage.setSourceProject(file.getProject());
            lineage.setSourceFile(file);
            lineage.setRawEvidence(response);

            // Simple parsing of LLM response
            for (String line : response.split("\n")) {
                if (line.startsWith("TARGET:"))
                    lineage.setTargetServiceName(line.substring(7).trim());
                if (line.startsWith("PROTOCOL:"))
                    lineage.setProtocol(line.substring(9).trim());
                if (line.startsWith("TYPE:"))
                    lineage.setConnectionType(line.substring(5).trim());
                if (line.startsWith("CONFIDENCE:")) {
                    try {
                        lineage.setConfidenceScore(Double.parseDouble(line.substring(11).trim()));
                    } catch (Exception ignored) {
                    }
                }
            }

            log.info("Discovered Dependency: {} -> {}", file.getProject().getName(), lineage.getTargetServiceName());
            return lineage;
        } catch (Exception e) {
            log.error("Failed to parse LLM lineage response", e);
            return null;
        }
    }

    public List<DependencyLineage> getLineageForProject(String projectName) {
        return lineageRepository.findBySourceProject_Name(projectName);
    }

    private String fetchSourceFromMinio(String storageKey) {
        try (InputStream stream = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(bucket)
                        .object(storageKey)
                        .build());
                BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
            return reader.lines().limit(100).collect(Collectors.joining("\n"));
        } catch (Exception e) {
            log.error("Error fetching from MinIO for lineage: {}", storageKey, e);
            return "";
        }
    }
}
