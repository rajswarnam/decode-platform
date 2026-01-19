package com.decode.ingestion.engine.controller;

import com.decode.ingestion.engine.service.ProjectDiscoveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.http.MediaType;
import com.decode.ingestion.engine.service.IngestionEventService;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import com.decode.ingestion.engine.domain.Project;

@RestController
@RequestMapping("/api/v1/ingestion")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = {"http://localhost:5173", "http://localhost:5174"})
public class IngestionController {

    private final ProjectDiscoveryService projectDiscoveryService;
    private final IngestionEventService ingestionEventService;

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamIngestionEvents() {
        return ingestionEventService.subscribe();
    }

    @GetMapping("/status")
    public ResponseEntity<List<Project>> getAllProjectStatus() {
        return ResponseEntity.ok(projectDiscoveryService.getAllProjects());
    }

    @GetMapping("/pipeline-status")
    public ResponseEntity<Map<String, Object>> getPipelineStatus(@RequestParam String projectName) {
        Map<String, Object> status = new HashMap<>();
        
        // Get project from ingestion-engine
        java.util.Optional<com.decode.ingestion.engine.domain.Project> projectOpt = 
            projectDiscoveryService.getAllProjects().stream()
                .filter(p -> p.getName().equals(projectName))
                .findFirst();
        
        if (projectOpt.isEmpty()) {
            status.put("error", "Project not found");
            return ResponseEntity.notFound().build();
        }
        
        com.decode.ingestion.engine.domain.Project project = projectOpt.get();
        status.put("projectName", project.getName());
        status.put("projectId", project.getId().toString());
        status.put("ingestionStatus", project.getStatus());
        status.put("filesIngested", project.getTotalFiles());
        
        // Check parsing status (call code-parser)
        try {
            String parserUrl = "http://code-parser:8080/api/parser/status?projectId=" + project.getId();
            org.springframework.web.client.RestTemplate restTemplate = new org.springframework.web.client.RestTemplate();
            Map<String, Object> parserStatus = restTemplate.getForObject(parserUrl, Map.class);
            if (parserStatus != null) {
                status.put("symbolCount", parserStatus.get("symbolCount"));
                status.put("parsingStatus", parserStatus.get("status"));
            }
        } catch (Exception e) {
            log.warn("Could not fetch parsing status: {}", e.getMessage());
            status.put("parsingStatus", "UNKNOWN");
        }
        
        // Check vectorization status (call vectorizer-service)
        try {
            String vectorizerUrl = "http://vectorizer-service:8080/api/vectorizer/status?projectId=" + project.getId();
            org.springframework.web.client.RestTemplate restTemplate = new org.springframework.web.client.RestTemplate();
            Map<String, Object> vectorizerStatus = restTemplate.getForObject(vectorizerUrl, Map.class);
            if (vectorizerStatus != null) {
                status.put("embeddingStatus", vectorizerStatus.get("status"));
                status.put("embeddingsCreated", vectorizerStatus.get("embeddingCount"));
            }
        } catch (Exception e) {
            log.warn("Could not fetch vectorization status: {}", e.getMessage());
            status.put("embeddingStatus", "UNKNOWN");
        }
        
        return ResponseEntity.ok(status);
    }

    @PostMapping("/git-clone")
    public ResponseEntity<String> cloneAndIngest(@RequestParam String gitUrl, @RequestParam(required = false) String groupName) {
        log.info("🚀 Received Git Clone Request: {}", gitUrl);

        String repoName = gitUrl.substring(gitUrl.lastIndexOf("/") + 1).replace(".git", "");
        // USE /tmp instead of /workspace to avoid persistent disk usage and IDE indexing triggers
        Path tempRoot = Path.of("/tmp/decode-repos"); 
        Path targetPath = tempRoot.resolve(repoName);

        // Pre-register project so it shows in UI immediately
        projectDiscoveryService.registerPendingProject(repoName, gitUrl, targetPath.toString(), groupName);

        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                log.info("Starting Async git clone of {} to {}", gitUrl, targetPath);
                
                // Update status to CLONING and send progress
                projectDiscoveryService.updateProjectStatus(repoName, "CLONING");
                ingestionEventService.sendEvent("🔄 Cloning repository: " + repoName);

                if (Files.exists(targetPath)) {
                    ingestionEventService.sendEvent("🧹 Cleaning existing directory...");
                    deleteDirectoryRecursively(targetPath);
                }
                Files.createDirectories(tempRoot);

                ingestionEventService.sendEvent("📥 Starting git clone from: " + gitUrl);
                try {
                    // Use JGit to avoid relying on system 'git' binary
                    Git.cloneRepository()
                        .setURI(gitUrl)
                        .setDirectory(targetPath.toFile())
                        .setCloneAllBranches(true)
                        .call()
                        .close();
                } catch (GitAPIException ge) {
                    log.error("JGit clone failed: {}", ge.getMessage());
                    ingestionEventService.sendEvent("❌ Git clone failed: " + ge.getMessage());
                    projectDiscoveryService.updateProjectStatus(repoName, "FAILED");
                    return;
                }

                ingestionEventService.sendEvent("✅ Git clone successful! Starting project discovery...");
                log.info("Git clone successful. Starting Ingestion...");
                
                // Update status to IN_PROGRESS before discovery
                projectDiscoveryService.updateProjectStatus(repoName, "IN_PROGRESS");
                
                projectDiscoveryService.discoverAndRegisterProjects(targetPath.toString(), gitUrl, repoName, groupName);
                
                // --- CLEANUP ---
                log.info("Ingestion complete. Deleting temporary files for: {}", repoName);
                ingestionEventService.sendEvent("🧹 Cleaning up temporary files...");
                deleteDirectoryRecursively(targetPath);
                ingestionEventService.sendEvent("✅ Ingestion complete for: " + repoName);

            } catch (Exception e) {
                log.error("Error executing git clone", e);
                ingestionEventService.sendEvent("❌ Error: " + e.getMessage());
                projectDiscoveryService.updateProjectStatus(repoName, "FAILED");
            }
        });

        return ResponseEntity.accepted().body("Git clone started for: " + repoName);
    }

    @PostMapping("/upload-zip")
    public ResponseEntity<?> uploadZip(@RequestParam("file") MultipartFile file, @RequestParam(required = false) String groupName) {
        if (file == null || file.isEmpty()) {
            log.error("ZIP upload failed: No file provided");
            return ResponseEntity.badRequest().body(Map.of("message", "No file provided"));
        }

        String originalFilename = file.getOriginalFilename();
        long fileSizeMB = file.getSize() / (1024 * 1024);
        log.info("📂 Received ZIP Upload: {} (size: {} MB / {} bytes)", originalFilename, fileSizeMB, file.getSize());

        String displayProjectName = (originalFilename != null && originalFilename.contains("."))
                ? originalFilename.substring(0, originalFilename.lastIndexOf('.'))
                : "Manual Upload";

        // Pre-register project so it shows in UI immediately
        projectDiscoveryService.registerPendingProject(displayProjectName, "manual-upload", "/tmp", groupName);
        projectDiscoveryService.updateProjectStatus(displayProjectName, "IN_PROGRESS");
        ingestionEventService.sendEvent("📦 Starting ZIP upload processing: " + displayProjectName + " (" + fileSizeMB + " MB)");

        // Process ZIP extraction asynchronously to avoid blocking
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            Path tempDir = null;
            try {
                tempDir = Files.createTempDirectory("decode-upload-");
                log.debug("Created temp directory: {}", tempDir);
                ingestionEventService.sendEvent("📂 Extracting ZIP archive...");

                // Extract ZIP file with progress reporting for large files
                int fileCount = 0;
                long totalBytesExtracted = 0;
                try (ZipInputStream zis = new ZipInputStream(file.getInputStream())) {
                    ZipEntry entry;
                    byte[] buffer = new byte[8192]; // Larger buffer for better performance (8KB instead of 1KB)
                    
                    while ((entry = zis.getNextEntry()) != null) {
                        // Security: Prevent zip slip vulnerability
                        File newFile = new File(tempDir.toFile(), entry.getName());
                        String canonicalPath = newFile.getCanonicalPath();
                        String canonicalBase = tempDir.toFile().getCanonicalPath();
                        if (!canonicalPath.startsWith(canonicalBase + File.separator)) {
                            log.error("ZIP upload failed: Invalid entry path detected (zip slip attack): {}", entry.getName());
                            ingestionEventService.sendEvent("❌ Security violation detected in ZIP file");
                            projectDiscoveryService.updateProjectStatus(displayProjectName, "FAILED");
                            return;
                        }

                        if (entry.isDirectory()) {
                            newFile.mkdirs();
                        } else {
                            newFile.getParentFile().mkdirs();
                            try (FileOutputStream fos = new FileOutputStream(newFile)) {
                                int len;
                                while ((len = zis.read(buffer)) > 0) {
                                    fos.write(buffer, 0, len);
                                    totalBytesExtracted += len;
                                }
                            }
                            fileCount++;
                            
                            // Progress reporting for large uploads (every 10000 files or 50MB)
                            if (fileCount % 10000 == 0 || totalBytesExtracted % (50 * 1024 * 1024) == 0) {
                                long extractedMB = totalBytesExtracted / (1024 * 1024);
                                ingestionEventService.sendEvent(
                                    String.format("📂 Extracted %d files (%.1f MB)...", fileCount, extractedMB)
                                );
                            }
                        }
                    }
                }
                
                long finalExtractedMB = totalBytesExtracted / (1024 * 1024);
                log.info("✅ Extracted {} files ({} MB) from ZIP", fileCount, finalExtractedMB);
                ingestionEventService.sendEvent(
                    String.format("✅ ZIP extraction complete: %d files (%.1f MB)", fileCount, finalExtractedMB)
                );

                ingestionEventService.sendEvent("🔍 Starting project discovery...");
                projectDiscoveryService.discoverAndRegisterProjects(tempDir.toString(), "manual-upload", displayProjectName, groupName);
                ingestionEventService.sendEvent("✅ Project registration complete: " + displayProjectName);
                
            } catch (IOException e) {
                log.error("ZIP upload failed: Error extracting ZIP file", e);
                ingestionEventService.sendEvent("❌ ZIP extraction failed: " + e.getMessage());
                projectDiscoveryService.updateProjectStatus(displayProjectName, "FAILED");
            } catch (Exception e) {
                log.error("ZIP upload failed: Unexpected error", e);
                ingestionEventService.sendEvent("❌ Upload failed: " + e.getMessage());
                projectDiscoveryService.updateProjectStatus(displayProjectName, "FAILED");
            } finally {
                // CLEANUP ZIP EXTRACT
                if (tempDir != null) {
                    log.debug("Cleaning up temp zip directory: {}", tempDir);
                    deleteDirectoryRecursively(tempDir);
                }
            }
        });

        return ResponseEntity.accepted().body(Map.of(
            "message", "ZIP upload accepted and processing started",
            "projectName", displayProjectName,
            "fileSizeMB", fileSizeMB
        ));
    }
    
    // Check line 131 for where existing code starts again
    
    private void deleteDirectoryRecursively(Path path) {
        try (java.util.stream.Stream<Path> walk = Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        } catch (IOException e) {
            log.error("Failed to cleanup directory: {}", path, e);
        }
    }

    @DeleteMapping("/all")
    public ResponseEntity<String> deleteAllProjects() {
        projectDiscoveryService.deleteAllProjects();
        return ResponseEntity.ok("All projects deleted from database.");
    }
}
