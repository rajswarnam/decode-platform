package com.decode.context.orchestrator.service;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
@RequiredArgsConstructor
@Slf4j
public class SnippetService {

    private final MinioClient minioClient;

    public String fetchSnippet(String storageKey, int startLine, int endLine) {
        log.info("Fetching snippet: {} [Lines {}-{}]", storageKey, startLine, endLine);
        try {
            // Fetch entire file based on stored key
            InputStream stream = minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket("decode-source-code")
                            .object(storageKey)
                            .build());

            String content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            String[] lines = content.split("\\R"); // OS-agnostic line split

            // Extract requested range (Safety bounds check)
            int safeStart = Math.max(0, startLine - 1); // 0-indexed
            int safeEnd = Math.min(lines.length, endLine);

            if (safeStart >= safeEnd) {
                return "// Error: Code range invalid or outdated.";
            }

            return IntStream.range(safeStart, safeEnd)
                    .mapToObj(i -> String.format("%4d | %s", i + 1, lines[i]))
                    .collect(Collectors.joining("\n"));

        } catch (Exception e) {
            log.error("Failed to load snippet from MinIO", e);
            return "// Error loading source code: " + e.getMessage();
        }
    }
}
