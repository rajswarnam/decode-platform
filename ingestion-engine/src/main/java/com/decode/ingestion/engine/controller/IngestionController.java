package com.decode.ingestion.engine.controller;

import com.decode.ingestion.engine.service.ProjectDiscoveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import com.decode.ingestion.engine.domain.Project;

@RestController
@RequestMapping("/api/v1/ingestion")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "http://localhost:5173")
public class IngestionController {

    private final ProjectDiscoveryService projectDiscoveryService;

    @GetMapping("/status")
    public ResponseEntity<List<Project>> getAllProjectStatus() {
        return ResponseEntity.ok(projectDiscoveryService.getAllProjects());
    }

    @PostMapping("/git-clone")
    public ResponseEntity<String> cloneAndIngest(@RequestParam String gitUrl) {
        log.info("🚀 Received Git Clone Request: {}", gitUrl);

        // In a real system, we would run 'git clone' to a temp directory.
        // For this demo, we simulate by scanning the workspace for a folder matching
        // the repo name.
        String repoName = gitUrl.substring(gitUrl.lastIndexOf("/") + 1).replace(".git", "");
        String simulatedPath = "/Users/kothuparotta/.gemini/antigravity/scratch/decode-workspace/" + repoName;

        // This would be replaced by actual git clone logic
        projectDiscoveryService.discoverAndRegisterProjects(simulatedPath, gitUrl, repoName);

        return ResponseEntity.ok("Git project queued for ingestion: " + repoName);
    }

    @PostMapping("/upload-zip")
    public ResponseEntity<String> uploadZip(@RequestParam("file") MultipartFile file) throws IOException {
        log.info("📂 Received ZIP Upload: {}", file.getOriginalFilename());

        Path tempDir = Files.createTempDirectory("decode-upload-");
        try (ZipInputStream zis = new ZipInputStream(file.getInputStream())) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File newFile = new File(tempDir.toFile(), entry.getName());
                if (entry.isDirectory()) {
                    newFile.mkdirs();
                } else {
                    newFile.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(newFile)) {
                        byte[] buffer = new byte[1024];
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }
            }
        }

        String originalFilename = file.getOriginalFilename();
        String displayProjectName = (originalFilename != null && originalFilename.contains("."))
                ? originalFilename.substring(0, originalFilename.lastIndexOf('.'))
                : "Manual Upload";

        projectDiscoveryService.discoverAndRegisterProjects(tempDir.toString(), "manual-upload", displayProjectName);

        return ResponseEntity.ok("Zip archive processed and projects registered.");
    }
}
