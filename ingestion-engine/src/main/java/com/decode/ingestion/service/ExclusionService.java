package com.decode.ingestion.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Fiscal Guardrail: Prevents binary artifacts and build noise from entering the
 * pipeline.
 * Protects the 250k TPM quota and Qdrant storage from pollution.
 */
@Service
@Slf4j
public class ExclusionService {

    // Compiled Binaries (Strict Exclude)
    private static final Set<String> BINARY_EXTENSIONS = new HashSet<>(Arrays.asList(
            "class", "jar", "war", "ear", "dll", "exe", "so", "dylib", "bin", "o", "obj", "a", "lib"));

    // Media & Office Documents (Phase 1 Exclude)
    private static final Set<String> MEDIA_EXTENSIONS = new HashSet<>(Arrays.asList(
            "jpg", "jpeg", "png", "gif", "bmp", "svg", "ico", "pdf",
            "doc", "docx", "xls", "xlsx", "ppt", "pptx"));

    // Raw Data Files (High TPM Risk)
    private static final Set<String> DATA_EXTENSIONS = new HashSet<>(Arrays.asList(
            "dat", "db", "sqlite", "mdb", "accdb", "dump", "bak"));

    // Compressed Archives (Extract First)
    private static final Set<String> ARCHIVE_EXTENSIONS = new HashSet<>(Arrays.asList(
            "zip", "tar", "gz", "rar", "7z", "tgz"));

    // Logs & Temporary Files
    private static final Set<String> TEMP_EXTENSIONS = new HashSet<>(Arrays.asList(
            "log", "tmp", "temp", "swp", "swo"));

    // Build & Dependency Directories
    private static final Set<String> EXCLUDED_DIRS = new HashSet<>(Arrays.asList(
            "target", "bin", "obj", "build", "dist", "out", "node_modules",
            ".gradle", ".mvn", "vendor", "packages", ".idea", ".vscode", ".vs", ".git", ".svn",
            "infra", "ingestion-engine", "code-parser", "vectorizer-service", "web-frontend",
            "llm-gateway-service", "api-gateway", "documentation-hub", "context-orchestrator", ".agent"));

    // Allowed Source Code Extensions
    private static final Set<String> ALLOWED_EXTENSIONS = new HashSet<>(Arrays.asList(
            "java", "c", "cpp", "h", "hpp", "cbl", "cob", "js", "ts", "jsx", "tsx", "py", "rb", "go", "rs",
            "xml", "yaml", "yml", "json", "properties", "conf", "aclf", "md", "txt", "rst", "sql",
            "aspx", "aspx.cs", "aspx.vb", "cs", "vb")); // ASP.NET extensions

    // File Size Limit (1MB for non-source files)
    private static final long MAX_FILE_SIZE_BYTES = 1_048_576; // 1MB

    /**
     * Determines if a file should be excluded from ingestion.
     * 
     * @param filePath Path to the file
     * @return true if file should be excluded, false if it should be processed
     */
    public boolean shouldExclude(Path filePath) {
        try {
            // Check if it's a directory
            if (Files.isDirectory(filePath)) {
                return shouldExcludeDirectory(filePath);
            }

            // Check if file is inside an excluded directory (e.g., .git/, target/, etc.)
            String pathString = filePath.toString().toLowerCase().replace('\\', '/');
            for (String excludedDir : EXCLUDED_DIRS) {
                if (pathString.contains("/" + excludedDir + "/") || pathString.contains("/" + excludedDir)) {
                    log.debug("Excluded (Inside excluded directory): {}", filePath);
                    return true;
                }
            }

            String fileName = filePath.getFileName().toString().toLowerCase();
            String extension = getFileExtension(fileName);

            // 1. Check Binary Extensions
            if (BINARY_EXTENSIONS.contains(extension)) {
                log.debug("Excluded (Binary): {}", filePath);
                return true;
            }

            // 2. Check Media/Office Extensions
            if (MEDIA_EXTENSIONS.contains(extension)) {
                log.debug("Excluded (Media/Office): {}", filePath);
                return true;
            }

            // 3. Check Data Files
            if (DATA_EXTENSIONS.contains(extension)) {
                log.warn("Excluded (Data File - TPM Risk): {}", filePath);
                return true;
            }

            // 4. Check Archives
            if (ARCHIVE_EXTENSIONS.contains(extension)) {
                log.info("Excluded (Archive - Extract First): {}", filePath);
                return true;
            }

            // 5. Check Temp Files
            if (TEMP_EXTENSIONS.contains(extension)) {
                log.debug("Excluded (Temp): {}", filePath);
                return true;
            }

            // 6. Check System Files
            if (fileName.equals(".ds_store") || fileName.equals("thumbs.db")) {
                log.debug("Excluded (System): {}", filePath);
                return true;
            }

            // 7. Check File Size (Circuit Breaker)
            long fileSize = Files.size(filePath);
            if (fileSize > MAX_FILE_SIZE_BYTES && !isSourceCode(extension)) {
                // For unknown extensions, check MIME type to see if it's text-based
                // This prevents excluding large source files with unknown extensions
                if (!ALLOWED_EXTENSIONS.contains(extension) && !extension.isEmpty()) {
                    // Unknown extension - check if it's text-based before excluding
                    if (isPlainText(filePath)) {
                        log.debug("Allowing large unknown extension file (text-based): {} ({} bytes)", filePath, fileSize);
                        // Treat as source code for size limit exemption
                    } else {
                        log.warn("Excluded (Size > 1MB, binary): {} ({} bytes)", filePath, fileSize);
                        return true;
                    }
                } else {
                    log.warn("Excluded (Size > 1MB): {} ({} bytes)", filePath, fileSize);
                    return true;
                }
            }

            // 8. MIME Type Verification (for extensionless files)
            if (extension.isEmpty() && !isPlainText(filePath)) {
                log.warn("Excluded (Binary MIME): {}", filePath);
                return true;
            }

            // 9. Unknown Extension Check (Permissive: Only exclude if explicitly in exclusion lists)
            // Changed from whitelist to blacklist approach:
            // - If extension is in ALLOWED_EXTENSIONS → Process (fast path)
            // - If extension is empty → Check MIME type (already done above)
            // - If extension is unknown but not in any exclusion list → Process (permissive)
            // - Only exclude if explicitly in BINARY, MEDIA, DATA, ARCHIVE, or TEMP lists
            
            // Fast path: Known good extensions
            if (ALLOWED_EXTENSIONS.contains(extension)) {
                // File is safe to process
                return false;
            }
            
            // Extensionless files: Already checked MIME type above
            if (extension.isEmpty()) {
                // If we got here, MIME type check passed, so it's safe
                return false;
            }
            
            // Unknown extension: Be permissive - only exclude if it's explicitly in exclusion lists
            // (We already checked BINARY, MEDIA, DATA, ARCHIVE, TEMP above)
            // If it's not in any exclusion list, allow it through
            log.debug("Allowing unknown extension '{}' for file: {} (not in exclusion lists)", extension, filePath);
            return false; // Allow unknown extensions that aren't explicitly excluded

        } catch (IOException e) {
            log.error("Error checking file: {}", filePath, e);
            return true; // Exclude on error (fail-safe)
        }
    }

    /**
     * Checks if a directory should be excluded.
     */
    private boolean shouldExcludeDirectory(Path dirPath) {
        Path fileNamePath = dirPath.getFileName();
        if (fileNamePath == null) {
            return false; // Root directory should not be excluded by name
        }
        String dirName = fileNamePath.toString().toLowerCase();
        if (EXCLUDED_DIRS.contains(dirName)) {
            log.debug("Excluded Directory: {}", dirPath);
            return true;
        }
        return false;
    }

    /**
     * Extracts file extension from filename.
     */
    private String getFileExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot > 0 && lastDot < fileName.length() - 1) {
            return fileName.substring(lastDot + 1).toLowerCase();
        }
        return "";
    }

    /**
     * Checks if extension is a known source code format.
     * Used to exempt from file size limits.
     */
    private boolean isSourceCode(String extension) {
        return Arrays.asList("java", "c", "cpp", "h", "cbl", "cob", "js", "ts", "jsx", "tsx", "py", 
                "aspx", "aspx.cs", "aspx.vb", "cs", "vb", "aclf", "xml", "html", "htm").contains(extension);
    }

    /**
     * Verifies file is plain text using MIME type detection.
     * Uses "Magic Bytes" to detect binary files even without extensions.
     */
    private boolean isPlainText(Path filePath) {
        try {
            String mimeType = Files.probeContentType(filePath);
            if (mimeType == null) {
                // Fallback: Check first 512 bytes for null bytes (binary indicator)
                byte[] sample = Files.readAllBytes(filePath);
                int checkLength = Math.min(sample.length, 512);
                for (int i = 0; i < checkLength; i++) {
                    if (sample[i] == 0) {
                        return false; // Contains null byte = binary
                    }
                }
                return true; // No null bytes = likely text
            }
            return mimeType.startsWith("text/") || mimeType.contains("xml") || mimeType.contains("json");
        } catch (IOException e) {
            log.warn("Could not determine MIME type for: {}", filePath);
            return false; // Exclude if uncertain
        }
    }

    /**
     * Logs exclusion statistics for monitoring.
     */
    public void logExclusionStats(int totalFiles, int excludedFiles) {
        double exclusionRate = (excludedFiles / (double) totalFiles) * 100;
        log.info("Exclusion Stats: {}/{} files excluded ({:.2f}% noise reduction)",
                excludedFiles, totalFiles, exclusionRate);
    }
}
