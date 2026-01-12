package com.decode.context.orchestrator.service;

import com.decode.context.orchestrator.domain.Symbol;
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
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SemanticExplorerService {

    private final VectorStore vectorStore;
    private final ChatClient.Builder chatClientBuilder;
    private final MinioClient minioClient;
    private final SymbolRepository symbolRepository;

    @Value("${minio.bucket}")
    private String bucket;

    private static final int MAX_CONTEXT_CHARS = 75000;

    public void exploreStream(String query, String domain, Consumer<String> progressConsumer,
            Consumer<String> answerChunkConsumer) {
        log.info("Executing Discovery: '{}'", query);
        if (progressConsumer != null)
            progressConsumer.accept("Initiating multi-pass logic discovery...");

        // 1. DIVERSE SEARCH
        var broadSearch = SearchRequest.builder().query(query).topK(100);
        if (domain != null && !domain.equalsIgnoreCase("General")) {
            broadSearch.filterExpression("domain == '" + domain + "'");
        }
        List<Document> allResults = new ArrayList<>(vectorStore.similaritySearch(broadSearch.build()));

        // 2. LOGIC SCAN
        String domainKeyword = query.toLowerCase()
                .replaceAll(
                        "\\b(what|are|the|different|services|usecases|functionality|how|is|in|a|an|exists|to|of|related)\\b",
                        "")
                .replaceAll("[\\?\\.]", "")
                .trim();

        if (!domainKeyword.isEmpty()) {
            if (progressConsumer != null)
                progressConsumer.accept("Scanning for Production Logic matching: '" + domainKeyword + "'...");
            allResults.addAll(vectorStore.similaritySearch(
                    SearchRequest.builder().query(domainKeyword + " ServiceImpl Controller").topK(30).build()));
        }

        // 3. Resolve & Filter
        List<SymbolMetadata> enrichedSymbols = new ArrayList<>();
        for (Document doc : allResults) {
            String sid = (String) doc.getMetadata().get("symbol_id");
            if (sid == null)
                continue;
            symbolRepository.findById(UUID.fromString(sid)).ifPresent(s -> {
                if (s.getSourceFile() != null)
                    enrichedSymbols.add(new SymbolMetadata(s, doc.getScore()));
            });
        }

        // 4. ARCHITECTURE PRIORITY SORT
        enrichedSymbols.sort((a, b) -> {
            String pA = (a.symbol.getSourceFile().getFilePath() != null)
                    ? a.symbol.getSourceFile().getFilePath().toLowerCase()
                    : "";
            String pB = (b.symbol.getSourceFile().getFilePath() != null)
                    ? b.symbol.getSourceFile().getFilePath().toLowerCase()
                    : "";
            int scoreA = 0;
            if (pA.contains("/main/"))
                scoreA += 100;
            if (pA.contains("impl") || pA.contains("controller"))
                scoreA += 100;
            if (pA.contains("test"))
                scoreA -= 500;
            int scoreB = 0;
            if (pB.contains("/main/"))
                scoreB += 100;
            if (pB.contains("impl") || pB.contains("controller"))
                scoreB += 100;
            if (pB.contains("test"))
                scoreB -= 500;
            return Integer.compare(scoreB, scoreA);
        });

        // 5. Build Context
        if (progressConsumer != null)
            progressConsumer.accept("Synchronizing context files and removing test noise...");
        StringBuilder contextBuilder = new StringBuilder();
        Set<String> seenFileNames = new HashSet<>();
        int included = 0;
        for (SymbolMetadata sm : enrichedSymbols) {
            String fName = sm.symbol.getSourceFile().getFileName();
            String sKey = sm.symbol.getSourceFile().getStorageKey();
            if (sKey == null || seenFileNames.contains(fName))
                continue;
            String code = fetchSourceFromMinio(sKey, 800);
            if (contextBuilder.length() + code.length() > MAX_CONTEXT_CHARS)
                break;
            boolean isPrimary = sKey.contains("/main/") && (sKey.contains("Impl") || sKey.contains("Controller"));
            contextBuilder.append("\n--- [").append(isPrimary ? "PRIMARY IMPLEMENTATION" : "CONTEXTUAL SUPPORT")
                    .append("] Path: ").append(sm.symbol.getSourceFile().getFilePath()).append(" ---\n");
            contextBuilder.append(code).append("\n");
            seenFileNames.add(fName);
            included++;
        }

        // 6. STREAMING REASONING
        if (progressConsumer != null)
            progressConsumer.accept("Reasoning over " + included + " production files...");
        String prompt = String.format(
                """
                        You are a Senior Solutions Architect creating a COMPREHENSIVE REDEVELOPMENT BLUEPRINT for enterprise applications.
                        Your goal is to provide sufficient detail for a development team to rebuild this system from scratch.

                        **CRITICAL FORMATTING REQUIREMENTS:**
                        1. Output ONLY well-formatted Markdown with proper spacing between ALL words
                        2. Use headers (##, ###) to organize sections
                        3. Use bullet points (-) for lists
                        4. Wrap code/method names in backticks: `methodName()`
                        5. Use code blocks (```) for schemas, examples, and multi-line code
                        6. Add blank lines between sections for readability

                        **COMPREHENSIVE ANALYSIS FRAMEWORK:**

                        ## 1. Use Cases & API Contracts
                        - List all endpoints with HTTP methods, paths, and descriptions
                        - Document request/response formats
                        - Specify query parameters, path variables, and headers

                        ## 2. Data Models & Schemas
                        - Extract ALL domain objects, DTOs, and entities
                        - Document field names, data types, and constraints (required, max length, format)
                        - Show relationships (one-to-many, many-to-many)
                        - Include database table schemas if evident
                        - Provide example JSON payloads

                        ## 3. Business Rules & Validation Logic
                        - Document all validation rules (email format, password strength, etc.)
                        - Explain business constraints (e.g., "account name must be unique")
                        - Describe calculation logic (e.g., interest rates, totals)
                        - List any constants, enums, or configuration values

                        ## 4. Security & Authorization
                        - Identify authentication mechanism (JWT, OAuth, Basic Auth, etc.)
                        - Document required roles, scopes, or permissions per endpoint
                        - Explain how Principal/User context is established
                        - Note any special security rules (e.g., "users can only access their own data")

                        ## 5. Error Handling & Edge Cases
                        - List possible error scenarios (not found, duplicate, validation failure)
                        - Document expected HTTP status codes (200, 400, 401, 404, 500)
                        - Explain exception handling patterns
                        - Describe fallback or retry logic


                        ## 6. Dependencies & Integrations
                        - Identify external services called (databases, APIs, message queues, SMTP, SMS gateways)
                        - **For each external service, document:**
                          - **Payload/Request Schema**: What data is sent? (field names, types, example values)
                          - **Response Schema**: What data is received back?
                          - **Example JSON/XML**: Provide concrete examples of request/response bodies
                          - **Authentication**: How does the service authenticate? (API keys, OAuth, etc.)
                        - Document repository/DAO methods and their purposes
                        - List third-party libraries or frameworks used
                        - Explain any event publishing or subscription patterns

                        ## 7. State Management & Transactions
                        - Describe transaction boundaries (@Transactional usage)
                        - Explain concurrency control (optimistic/pessimistic locking)
                        - Document audit logging or change tracking
                        - Note any caching strategies

                        ## 8. Non-Functional Requirements (if evident)
                        - Performance considerations (pagination, lazy loading)
                        - Scalability patterns (stateless design, async processing)
                        - Rate limiting or throttling
                        - Logging and monitoring hooks

                        **Source Code Context:**
                        %s

                        **User Question:**
                        %s

                        Provide a COMPLETE, production-ready blueprint with all 8 dimensions covered. Use clear Markdown formatting with proper spacing.
                        """,
                contextBuilder.toString(), query);

        log.info("Requesting streaming synthesis...");
        // Use toIterable() to synchronously iterate tokens on the executor thread
        chatClientBuilder.build().prompt(prompt).stream().chatResponse().toIterable().forEach(response -> {
            if (response.getResult() != null && response.getResult().getOutput() != null) {
                String text = response.getResult().getOutput().getText();
                if (text != null && !text.isEmpty()) {
                    answerChunkConsumer.accept(text);
                }
            }
        });

        if (progressConsumer != null)
            progressConsumer.accept("Mapping complete.");
    }

    private String fetchSourceFromMinio(String storageKey, int maxLines) {
        if (storageKey == null || storageKey.isEmpty())
            return "";
        try (InputStream is = minioClient.getObject(GetObjectArgs.builder().bucket(bucket).object(storageKey).build());
                BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            return reader.lines().limit(maxLines).collect(Collectors.joining("\n"));
        } catch (Exception e) {
            return "// Source unavailable: " + storageKey;
        }
    }

    private static class SymbolMetadata {
        final Symbol symbol;

        SymbolMetadata(Symbol s, double sc) {
            this.symbol = s;
        }
    }

    public String explore(String query, String domain) {
        StringBuilder sb = new StringBuilder();
        exploreStream(query, domain, null, sb::append);
        return sb.toString();
    }

    public String callLlmForRefinement(String prompt) {
        log.info("Calling LLM for blueprint refinement...");
        try {
            return chatClientBuilder.build()
                    .prompt(prompt)
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("Error calling LLM for refinement", e);
            throw new RuntimeException("Failed to refine blueprint: " + e.getMessage());
        }
    }
}
