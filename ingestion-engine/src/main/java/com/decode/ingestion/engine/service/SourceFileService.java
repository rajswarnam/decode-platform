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

    @Transactional
    public void ingestProjectFiles(Project project) {
        Path basePath = Paths.get(project.getBasePath());
        log.info("Ingesting files for project: {} at {}", project.getName(), basePath);

        project.setStatus("IN_PROGRESS");
        project.setIngestionProgress(0);

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

                            projectRepository.save(project);
                        });
            }

            // Cleanup stale files that were not updated in this run
            long deletedCount = sourceFileRepository.deleteByProjectAndLastIndexedBefore(project, ingestionStartTime);
            if (deletedCount > 0) {
                log.info("Cleaned up {} stale source files for project: {}", deletedCount, project.getName());
            }

            project.setStatus("COMPLETED");
            project.setIngestionProgress(100);
            project.setCurrentFile(null);
            project.setEstimatedRemainingSeconds(0L);
            projectRepository.save(project);

        } catch (IOException e) {
            log.error("Failed to walk project path for files", e);
            project.setStatus("FAILED");
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
