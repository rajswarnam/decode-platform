package com.decode.code.parser.service;

import com.decode.code.parser.domain.Project;
import com.decode.code.parser.domain.SourceFile;
import com.decode.code.parser.domain.Symbol;
import com.decode.code.parser.dto.ParsedSymbol;
import com.decode.code.parser.repository.ProjectRepository;
import com.decode.code.parser.repository.SourceFileRepository;
import com.decode.code.parser.repository.SymbolRepository;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.messages.Item;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ParserOrchestratorService {

    private final List<LanguageParser> parsers;
    private final ProjectRepository projectRepository;
    private final SourceFileRepository sourceFileRepository;
    private final SymbolRepository symbolRepository;
    private final MinioClient minioClient;
    
    // We need a RestTemplate to trigger downstream Vectorizer
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${minio.bucket:decode-bucket}")
    private String bucketName;

    @Value("${vectorizer.url:http://vectorizer-service:8080}")
    private String vectorizerUrl;

    public void processProject(Project project) {
        try {
            log.info("triggering parsing for project: {}", project.getName());
            processProjectFromMinio(project);
            
            // CHAIN: Trigger Vectorizer
            triggerVectorizer(project);
        } catch (Exception e) {
            log.error("Parsing failed for project {}", project.getName(), e);
        }
    }

    private void triggerVectorizer(Project project) {
        try {
            String url = vectorizerUrl + "/api/vectorizer/trigger?projectId=" + project.getId();
            log.info("Triggering Vectorizer: {}", url);
            restTemplate.postForEntity(url, null, String.class);
        } catch (Exception e) {
            log.error("Failed to trigger vectorizer: {}", e.getMessage());
        }
    }

    private void processProjectFromMinio(Project project) throws Exception {
        String projectPrefix = project.getId().toString();
        log.info("Scanning MinIO Bucket '{}' for Project: {} (Prefix: {})", bucketName, project.getName(), projectPrefix);

        Iterable<Result<Item>> results = minioClient.listObjects(
                ListObjectsArgs.builder()
                        .bucket(bucketName)
                        .prefix(projectPrefix)
                        .recursive(true)
                        .build());

        for (Result<Item> result : results) {
            Item item = result.get();
            String objectKey = item.objectName();
            if (objectKey.endsWith("/")) continue; 

            processMinioObject(project, objectKey);
        }
    }

    private void processMinioObject(Project project, String objectKey) {
        if (shouldIgnore(objectKey)) {
            // log.debug("Skipping ignored/generated file: {}", objectKey);
            return;
        }

        File tempFile = null;
        try {
            try (InputStream stream = minioClient.getObject(
                    GetObjectArgs.builder().bucket(bucketName).object(objectKey).build())) {

                String extension = "";
                int i = objectKey.lastIndexOf('.');
                if (i > 0) extension = objectKey.substring(i);

                tempFile = Files.createTempFile("parser-", extension).toFile();
                Files.copy(stream, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            if (isBinaryFile(tempFile)) {
                Files.deleteIfExists(tempFile.toPath());
                return;
            }

            for (LanguageParser parser : parsers) {
                if (parser.supports(tempFile)) {
                    List<ParsedSymbol> symbols = parser.parseFile(tempFile);
                    saveResults(project, objectKey, tempFile.getName(), symbols);
                    return;
                }
            }
        } catch (Exception e) {
            log.error("Failed to process object: {}", objectKey, e);
        } finally {
            if (tempFile != null && tempFile.exists()) tempFile.delete();
        }
    }

    private void saveResults(Project project, String storageKey, String fileName, List<ParsedSymbol> symbols) {
        SourceFile sourceFile = sourceFileRepository.findByProjectAndFilePath(project, storageKey)
                .orElse(new SourceFile());

        sourceFile.setProject(project);
        sourceFile.setFilePath(storageKey);
        sourceFile.setStorageKey(storageKey);
        sourceFile.setFileName(fileName);
        sourceFile.setExtension(fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.')) : "");
        sourceFile = sourceFileRepository.save(sourceFile);

        int added = 0;
        for (ParsedSymbol dto : symbols) {
            // Check if symbol exists to avoid duplicates? Ideally yes.
            // For now, simple insert
            Symbol symbol = new Symbol();
            symbol.setSourceFile(sourceFile);
            symbol.setName(dto.getName());
            symbol.setCategory(dto.getCategory());
            symbol.setDataType(dto.getType());
            symbol.setStartLine(dto.getStartLine());
            symbol.setEndLine(dto.getEndLine());
            symbolRepository.save(symbol);
            added++;
        }
        if (added > 0) log.info("Saved {} symbols for {}", added, storageKey);
    }

    private boolean isBinaryFile(File file) {
        // Simple heuristic
        return false; 
    }

    private boolean shouldIgnore(String key) {
        String k = key.toLowerCase();
        return k.contains("/generated/") || 
               k.contains("java-xjc") || 
               k.contains("java-gen") || 
               k.contains("/test/") ||
               k.contains("/target/") || 
               k.contains("/build/") || 
               k.contains("/node_modules/") ||
               k.contains(".mvn") ||
               k.contains("/.git/") ||  // Exclude .git directory files
               k.startsWith(".git/") ||  // Exclude .git files at root
               k.endsWith(".min.js") ||
               k.endsWith(".map");
    }
}
