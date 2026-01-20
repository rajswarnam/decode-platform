package com.decode.code.parser.service;

import com.decode.code.parser.domain.Project;
import com.decode.code.parser.domain.SourceFile;
import com.decode.code.parser.domain.Symbol;
import com.decode.code.parser.domain.SymbolRelationship;
import com.decode.code.parser.dto.ParsedSymbol;
import com.decode.code.parser.dto.ParsedRelationship;
import com.decode.code.parser.repository.ProjectRepository;
import com.decode.code.parser.repository.SourceFileRepository;
import com.decode.code.parser.repository.SymbolRepository;
import com.decode.code.parser.repository.SymbolRelationshipRepository;
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
    private final SymbolRelationshipRepository relationshipRepository;
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
        // First, check if vectorizer service is healthy/ready
        if (!isVectorizerServiceReady()) {
            log.warn("Vectorizer service is not ready. Skipping trigger for project: {}. " +
                    "Files are parsed but NOT vectorized. Vectorizer will process on next ingestion or manual trigger.",
                    project.getName());
            return;
        }
        
        int maxRetries = 3; // Reduced retries since we check readiness first
        int retryDelayMs = 2000; // 2 seconds between retries
        
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                String url = vectorizerUrl + "/api/vectorizer/trigger?projectId=" + project.getId();
                log.info("Triggering Vectorizer (attempt {}/{}): {}", attempt, maxRetries, url);
                
                ResponseEntity<String> response = restTemplate.postForEntity(url, null, String.class);
                
                if (response.getStatusCode().is2xxSuccessful()) {
                    log.info("✅ Successfully triggered vectorizer for project: {}", project.getName());
                    return;
                } else {
                    log.warn("Vectorizer returned non-2xx status: {}", response.getStatusCode());
                }
            } catch (org.springframework.web.client.ResourceAccessException e) {
                // Connection refused or service not ready
                if (attempt < maxRetries) {
                    log.warn("Vectorizer service not ready (attempt {}/{}), retrying in {}ms: {}", 
                            attempt, maxRetries, retryDelayMs, e.getMessage());
                    try {
                        Thread.sleep(retryDelayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.error("Interrupted while waiting for retry", ie);
                        break;
                    }
                } else {
                    log.warn("Failed to trigger vectorizer after {} attempts: {}", maxRetries, e.getMessage());
                    log.warn("Files were parsed but NOT vectorized. Vectorizer may still be starting up.");
                    log.warn("You can manually trigger vectorization via API or it will be processed on next ingestion.");
                }
            } catch (Exception e) {
                log.error("Failed to trigger vectorizer (attempt {}/{}): {}", attempt, maxRetries, e.getMessage());
                if (attempt == maxRetries) {
                    log.error("Vectorization failed for project: {}. Files are parsed but NOT vectorized.", project.getName());
                }
                break; // Don't retry for other exceptions
            }
        }
    }
    
    /**
     * Check if vectorizer service is ready/healthy
     * Tries to connect to health endpoint or trigger endpoint
     */
    private boolean isVectorizerServiceReady() {
        try {
            // Try health endpoint first (if available)
            String healthUrl = vectorizerUrl.replace("/api/vectorizer", "") + "/actuator/health";
            try {
                ResponseEntity<String> healthResponse = restTemplate.getForEntity(healthUrl, String.class);
                if (healthResponse.getStatusCode().is2xxSuccessful()) {
                    log.debug("Vectorizer service health check passed");
                    return true;
                }
            } catch (Exception e) {
                log.debug("Health endpoint not available, trying direct connection");
            }
            
            // Fallback: Try a simple GET to the base URL
            String baseUrl = vectorizerUrl.replace("/api/vectorizer", "");
            try {
                restTemplate.getForEntity(baseUrl, String.class);
                return true;
            } catch (Exception e) {
                log.debug("Vectorizer service not reachable: {}", e.getMessage());
                return false;
            }
        } catch (Exception e) {
            log.debug("Error checking vectorizer service readiness: {}", e.getMessage());
            return false;
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

        int totalFiles = 0;
        int filesProcessed = 0;
        int filesWithSymbols = 0;
        int filesIgnored = 0;
        int filesSkipped = 0;

        for (Result<Item> result : results) {
            Item item = result.get();
            String objectKey = item.objectName();
            if (objectKey.endsWith("/")) continue; 

            totalFiles++;
            
            if (shouldIgnore(objectKey)) {
                filesIgnored++;
                continue;
            }

            boolean hadSymbols = processMinioObject(project, objectKey);
            if (hadSymbols) {
                filesProcessed++;
                filesWithSymbols++;
            } else {
                filesSkipped++;
            }
        }

        log.info("✅ Parsing completed for project: {} | Total files: {} | Processed: {} ({} with symbols) | Ignored: {} | Skipped: {}", 
                project.getName(), totalFiles, filesProcessed, filesWithSymbols, filesIgnored, filesSkipped);
    }

    private boolean processMinioObject(Project project, String objectKey) {
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
                return false;
            }

            for (LanguageParser parser : parsers) {
                if (parser.supports(tempFile)) {
                    List<ParsedSymbol> symbols = parser.parseFile(tempFile);
                    List<ParsedRelationship> relationships = parser.extractRelationships(tempFile, symbols);
                    saveResults(project, objectKey, tempFile.getName(), symbols, relationships);
                    return !symbols.isEmpty();
                }
            }
            
            // No parser matched - log for debugging
            String extension = "";
            int dotIndex = objectKey.lastIndexOf('.');
            if (dotIndex > 0 && dotIndex < objectKey.length() - 1) {
                extension = objectKey.substring(dotIndex);
            }
            log.debug("No parser found for file: {} (extension: {})", objectKey, extension.isEmpty() ? "none" : extension);
            return false;
        } catch (Exception e) {
            log.error("Failed to process object: {}", objectKey, e);
            return false;
        } finally {
            if (tempFile != null && tempFile.exists()) tempFile.delete();
        }
    }

    private void saveResults(Project project, String storageKey, String fileName, 
                            List<ParsedSymbol> symbols, List<ParsedRelationship> relationships) {
        SourceFile sourceFile = sourceFileRepository.findByProjectAndFilePath(project, storageKey)
                .orElse(new SourceFile());

        sourceFile.setProject(project);
        sourceFile.setFilePath(storageKey);
        sourceFile.setStorageKey(storageKey);
        sourceFile.setFileName(fileName);
        sourceFile.setExtension(fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.')) : "");
        sourceFile = sourceFileRepository.save(sourceFile);

        // Save symbols and build a map for relationship resolution
        java.util.Map<String, Symbol> symbolMap = new java.util.HashMap<>();
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
            symbol = symbolRepository.save(symbol);
            
            // Index by name for relationship lookup (simple approach - may need to handle duplicates better)
            if (!symbolMap.containsKey(dto.getName())) {
                symbolMap.put(dto.getName(), symbol);
            }
            added++;
        }
        if (added > 0) log.info("Saved {} symbols for {}", added, storageKey);
        
        // Save relationships
        int relAdded = 0;
        for (ParsedRelationship relDto : relationships) {
            Symbol sourceSymbol = symbolMap.get(relDto.getSourceSymbolName());
            Symbol targetSymbol = symbolMap.get(relDto.getTargetSymbolName());
            
            // Also check if symbols exist in database (for cross-file relationships)
            if (sourceSymbol == null) {
                sourceSymbol = symbolRepository.findByName(relDto.getSourceSymbolName()).orElse(null);
                if (sourceSymbol != null && !sourceSymbol.getSourceFile().getProject().getId().equals(project.getId())) {
                    sourceSymbol = null; // Don't create relationships across projects
                }
            }
            if (targetSymbol == null) {
                targetSymbol = symbolRepository.findByName(relDto.getTargetSymbolName()).orElse(null);
                if (targetSymbol != null && !targetSymbol.getSourceFile().getProject().getId().equals(project.getId())) {
                    targetSymbol = null; // Don't create relationships across projects
                }
            }
            
            // Only save if both symbols exist
            if (sourceSymbol != null && targetSymbol != null) {
                // Create final copies for lambda expression
                final Symbol finalSourceSymbol = sourceSymbol;
                final Symbol finalTargetSymbol = targetSymbol;
                final String finalRelationshipType = relDto.getRelationshipType();
                
                // Check if relationship already exists to avoid duplicates
                boolean exists = relationshipRepository.findBySourceSymbol(finalSourceSymbol).stream()
                        .anyMatch(r -> r.getTargetSymbol().getId().equals(finalTargetSymbol.getId()) 
                                && r.getRelationshipType().equals(finalRelationshipType));
                
                if (!exists) {
                    SymbolRelationship relationship = new SymbolRelationship();
                    relationship.setSourceSymbol(sourceSymbol);
                    relationship.setTargetSymbol(targetSymbol);
                    relationship.setRelationshipType(relDto.getRelationshipType());
                    relationship.setSourceLine(relDto.getSourceLine());
                    relationship.setSourceColumn(relDto.getSourceColumn());
                    relationship.setContext(relDto.getContext());
                    relationshipRepository.save(relationship);
                    relAdded++;
                }
            }
        }
        if (relAdded > 0) log.info("Saved {} relationships for {}", relAdded, storageKey);
    }

    private boolean isBinaryFile(File file) {
        // Simple heuristic
        return false; 
    }

    private boolean shouldIgnore(String key) {
        String k = key.toLowerCase();
        
        // Directories to ignore
        if (k.contains("/generated/") || 
            k.contains("java-xjc") || 
            k.contains("java-gen") || 
            k.contains("/test/") ||
            k.contains("/target/") || 
            k.contains("/build/") || 
            k.contains("/node_modules/") ||
            k.contains(".mvn") ||
            k.contains("/.git/") ||  // Exclude .git directory files
            k.startsWith(".git/") ||  // Exclude .git files at root
            k.contains("/.swagger-codegen/") ||
            k.contains("/.tx/")) {
            return true;
        }
        
        // File patterns to ignore
        if (k.endsWith(".min.js") ||
            k.endsWith(".map") ||
            k.endsWith("/dockerfile") ||
            k.endsWith("/version") ||
            k.endsWith("/readme") ||
            k.endsWith("/readme.md") ||
            k.endsWith("/.gitignore") ||
            k.endsWith("/.gitattributes") ||
            k.endsWith("/.gitkeep") ||
            k.endsWith("/makefile") ||
            k.endsWith("/makefile.am") ||
            k.endsWith("/makefile.in") ||
            k.contains("/.idea/") ||
            k.contains("/.vscode/") ||
            k.contains("/.eclipse/")) {
            return true;
        }
        
        // Extensionless files that aren't parsable (unless explicitly handled)
        String fileName = key.substring(key.lastIndexOf('/') + 1);
        if (fileName.contains(".") == false && !fileName.isEmpty()) {
            // Allow only known extensionless files that might be parsed
            // Most extensionless files (like VERSION, README, Dockerfile, etc.) are already filtered above
            return true;
        }
        
        return false;
    }
}
