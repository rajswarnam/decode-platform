package com.decode.ingestion.engine.service;

import com.decode.ingestion.engine.domain.Project;
import com.decode.ingestion.engine.domain.SourceFile;
import com.decode.ingestion.engine.repository.ProjectRepository;
import com.decode.ingestion.engine.repository.SourceFileRepository;
import com.decode.ingestion.service.ExclusionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.stream.Stream;
import org.springframework.util.DigestUtils;

@Service
@RequiredArgsConstructor
@Slf4j
public class SourceFileService {

    private final SourceFileRepository sourceFileRepository;
    private final ProjectRepository projectRepository;
    private final StorageService storageService;
    private final ExclusionService exclusionService;
    private final IngestionEventService ingestionEventService;

    public void ingestProjectFiles(Project project) {
        Path basePath = Paths.get(project.getBasePath());
        log.info("Ingesting files for project: {} at {}", project.getName(), basePath);

        project.setStatus("IN_PROGRESS");
        project.setStatus("IN_PROGRESS");
        project.setIngestionProgress(0);
        project.setCurrentFile("Analyzing file structure...");
        projectRepository.save(project);
        ingestionEventService.sendProgress(project);

        try (Stream<Path> stream = Files.walk(basePath)) {
            long totalFiles = Files.walk(basePath)
                    .filter(Files::isRegularFile)
                    .filter(path -> !exclusionService.shouldExclude(path))
                    .count();

            if (totalFiles == 0) {
                project.setTotalFiles(totalFiles);
                project.setProcessedFiles(0L);
                projectRepository.save(project);
                return;
            }

            project.setTotalFiles(totalFiles);
            final long[] count = { 0 };
            final long startTimeMillis = System.currentTimeMillis();
            final LocalDateTime ingestionStartTime = LocalDateTime.now();

            try (Stream<Path> walkStream = Files.walk(basePath)) {
                walkStream.filter(Files::isRegularFile)
                        .filter(path -> !exclusionService.shouldExclude(path))
                        .forEach(path -> {
                            project.setCurrentFile(path.getFileName().toString());
                            registerAndUploadFile(project, path);
                            count[0]++;
                            project.setProcessedFiles(count[0]);

                            int progress = (int) ((count[0] * 100) / totalFiles);
                            project.setIngestionProgress(progress);

                            // Estimate time remaining
                            long elapsed = System.currentTimeMillis() - startTimeMillis;
                            if (count[0] > 0) {
                                long avgTimePerFile = elapsed / count[0];
                                long remainingFiles = totalFiles - count[0];
                                project.setEstimatedRemainingSeconds((remainingFiles * avgTimePerFile) / 1000);
                            }

                            if (count[0] % 5 == 0) {
                                ingestionEventService.sendProgress(project);
                            }

                            projectRepository.save(project);
                        });
            }

            // Cleanup stale files that were not updated in this run
            cleanupStaleFiles(project, ingestionStartTime);

            project.setStatus("COMPLETED");
            project.setIngestionProgress(100);
            project.setCurrentFile(null);
            project.setEstimatedRemainingSeconds(0L);
            projectRepository.save(project);
            // Save should be immediate with Spring Data JPA - no need for flush
            ingestionEventService.sendProgress(project); // Final update

            // Small delay to ensure database transaction is committed and visible to other services
            try {
                Thread.sleep(1000); // 1 second delay to ensure project is visible to code-parser
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // TRIGGER DOWNSTREAM PIPELINE
            triggerCodeParsing(project);

        } catch (IOException e) {
            log.error("Failed to walk project path for files", e);
            project.setStatus("FAILED");
            project.setCurrentFile("Error: " + e.getMessage());
            projectRepository.save(project);
            ingestionEventService.sendProgress(project); // Error update
        }
    }

    private void triggerCodeParsing(Project project) {
        try {
            String parserrUrl = "http://code-parser:8080/api/parser/trigger?projectId=" + project.getId();
            log.info("🚀 Triggering Code Parser: {} for project: {}", parserrUrl, project.getName());
            
            org.springframework.web.client.RestTemplate restTemplate = new org.springframework.web.client.RestTemplate();
            org.springframework.http.ResponseEntity<String> response = restTemplate.postForEntity(parserrUrl, null, String.class);
            
            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("✅ Code Parser triggered successfully: {}", response.getBody());
                ingestionEventService.sendEvent("✅ Code parsing triggered for: " + project.getName());
            } else {
                log.error("❌ Code Parser returned error status: {}", response.getStatusCode());
                ingestionEventService.sendEvent("⚠️ Code parser returned error: " + response.getStatusCode());
            }
        } catch (org.springframework.web.client.ResourceAccessException e) {
            log.error("❌ Failed to connect to code-parser service. Is it running? Error: {}", e.getMessage());
            ingestionEventService.sendEvent("❌ Failed to trigger code parser: Service unreachable. Check if code-parser is running.");
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            log.error("❌ Code Parser returned HTTP error: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            ingestionEventService.sendEvent("❌ Code parser error: " + e.getStatusCode() + " - Project may not exist in parser database.");
        } catch (Exception e) {
            log.error("❌ Failed to trigger code parser: {}", e.getMessage(), e);
            ingestionEventService.sendEvent("❌ Failed to trigger code parser: " + e.getMessage());
        }
    }

    @Transactional
    public void cleanupStaleFiles(Project project, LocalDateTime ingestionStartTime) {
        long deletedCount = sourceFileRepository.deleteByProjectAndLastIndexedBefore(project, ingestionStartTime);
        if (deletedCount > 0) {
            log.info("Cleaned up {} stale source files for project: {}", deletedCount, project.getName());
        }
    }

    private void registerAndUploadFile(Project project, Path path) {
        String absolutePath = path.toString();
        String fileName = path.getFileName().toString();
        String extension = "";
        int i = fileName.lastIndexOf('.');
        if (i > 0) {
            extension = fileName.substring(i);
        }

        SourceFile sourceFile = sourceFileRepository.findByProjectAndFilePath(project, absolutePath)
                .orElse(new SourceFile());

        String currentHash = "";
        try (InputStream is = Files.newInputStream(path)) {
            currentHash = DigestUtils.md5DigestAsHex(is);
        } catch (IOException e) {
            log.error("Failed to calculate hash for file: {}", absolutePath, e);
        }

        // Optimization: Skip if content hash hasn't changed
        if (sourceFile.getContentHash() != null && sourceFile.getContentHash().equals(currentHash)) {
            log.debug("Skipping unchanged file: {}", fileName);
            sourceFile.setLastIndexed(LocalDateTime.now());
            sourceFileRepository.save(sourceFile);
            return;
        }

        sourceFile.setProject(project);
        sourceFile.setFilePath(absolutePath);
        sourceFile.setFileName(fileName);
        sourceFile.setExtension(extension);
        sourceFile.setLastIndexed(LocalDateTime.now());
        sourceFile.setContentHash(currentHash);

        // Generate MinIO object key: project_id/relative_path
        String relativePath = Paths.get(project.getBasePath()).relativize(path).toString();
        String objectKey = project.getId().toString() + "/" + relativePath;

        try {
            String storageKey = storageService.uploadFile(path, objectKey);
            sourceFile.setStorageKey(storageKey);
            sourceFileRepository.save(sourceFile);
            log.debug("Registered and uploaded file: {}", relativePath);
        } catch (Exception e) {
            log.error("Failed to process file: {}", absolutePath, e);
        }
    }
}
