package com.decode.code.parser;

import com.decode.code.parser.domain.Project;
import com.decode.code.parser.domain.SourceFile;
import com.decode.code.parser.domain.Symbol;
import com.decode.code.parser.dto.ParsedSymbol;
import com.decode.code.parser.repository.ProjectRepository;
import com.decode.code.parser.repository.SourceFileRepository;
import com.decode.code.parser.repository.SymbolRepository;
import com.decode.code.parser.service.LanguageParser;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.messages.Item;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;

@Component
@RequiredArgsConstructor
@Slf4j
public class ParserRunner implements CommandLineRunner {

    private final List<LanguageParser> parsers;
    private final ProjectRepository projectRepository;
    private final SourceFileRepository sourceFileRepository;
    private final SymbolRepository symbolRepository;
    private final MinioClient minioClient;

    @Value("${minio.bucket:decode-bucket}")
    private String bucketName;

    @Override
    @Transactional
    public void run(String... args) throws Exception {
        log.info("--- Starting Code Parser Batch Job (Cloud-Native Mode) ---");

        List<Project> projects = projectRepository.findAll();
        log.info("Found {} projects to process", projects.size());

        if (projects.isEmpty()) {
            log.warn("No projects found in DB. Please run Ingestion Engine first.");
            return;
        }

        for (Project project : projects) {
            try {
                processProjectFromMinio(project);
            } catch (Exception e) {
                log.error("MinIO Scan failed for project {}. Falling back to local/pvc scan.", project.getName(), e);
                processProjectLocal(project);
            }
        }

        log.info("--- Code Parser Batch Job Complete ---");
    }

    private void processProjectFromMinio(Project project) throws Exception {
        String projectPrefix = project.getId().toString();
        log.info("Scanning MinIO Bucket '{}' for Project: {} (Prefix: {})", bucketName, project.getName(),
                projectPrefix);

        // List objects with prefix = project ID
        Iterable<Result<Item>> results = minioClient.listObjects(
                ListObjectsArgs.builder()
                        .bucket(bucketName)
                        .prefix(projectPrefix)
                        .recursive(true)
                        .build());

        for (Result<Item> result : results) {
            Item item = result.get();
            String objectKey = item.objectName();

            // Filter irrelevant files
            if (objectKey.endsWith("/"))
                continue; // Skip directories

            // Skip .git, etc. if needed

            log.info("Streaming from Cloud: {}", objectKey);
            processMinioObject(project, objectKey);
        }
    }

    private void processMinioObject(Project project, String objectKey) {
        File tempFile = null;
        try {
            // 1. Download stream to Temp File
            try (InputStream stream = minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectKey)
                            .build())) {

                String extension = "";
                int i = objectKey.lastIndexOf('.');
                if (i > 0) {
                    extension = objectKey.substring(i);
                }

                tempFile = Files.createTempFile("parser-", extension).toFile();
                Files.copy(stream, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            if (isBinaryFile(tempFile)) {
                log.warn("Skipping binary file detected by magic bytes: {}", objectKey);
                Files.deleteIfExists(tempFile.toPath()); // Use Path for deleteIfExists
                return;
            }

            // 2. Parse using existing Logic
            for (LanguageParser parser : parsers) {
                if (parser.supports(tempFile)) { // LanguageParser usually checks extension
                    // Trick: We renamed the temp file to have the correct extension
                    List<ParsedSymbol> symbols = parser.parseFile(tempFile);

                    // 3. Save with Cloud Key as Path
                    saveResults(project, objectKey, tempFile.getName(), symbols);
                    return; // Done
                }
            }

        } catch (Exception e) {
            log.error("Failed to process object: {}", objectKey, e);
        } finally {
            // 4. Cleanup
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    // Legacy / Local Fallback
    private void processProjectLocal(Project project) {
        String dbBasePath = project.getBasePath();
        File localDir = resolveLocalPath(dbBasePath);

        log.info("Processing Project Locally: {} (Path: {})", project.getName(), localDir.getAbsolutePath());

        if (!localDir.exists()) {
            log.error("Project path does not exist in this environment: {}", localDir.getAbsolutePath());
            return;
        }

        try (Stream<Path> stream = Files.walk(localDir.toPath())) {
            stream.filter(Files::isRegularFile)
                    .forEach(path -> {
                        try {
                            processLocalFile(project, path.toFile(), dbBasePath, localDir);
                        } catch (Exception e) {
                            log.error("Error processing local file", e);
                        }
                    });
        } catch (IOException e) {
            log.error("Failed to walk project path", e);
        }
    }

    private void processLocalFile(Project project, File file, String dbProjectRoot, File localProjectRoot) {
        for (LanguageParser parser : parsers) {
            if (parser.supports(file)) {
                try {
                    List<ParsedSymbol> symbols = parser.parseFile(file);

                    // Calculate relative path for DB consistency
                    String absolutePath = file.getAbsolutePath();
                    String relativePath = absolutePath.startsWith(localProjectRoot.getAbsolutePath())
                            ? absolutePath.substring(localProjectRoot.getAbsolutePath().length())
                            : file.getName();
                    if (relativePath.startsWith("/"))
                        relativePath = relativePath.substring(1);

                    // Normalize to Project/Path format
                    String storageKey = project.getName() + "/" + relativePath;

                    saveResults(project, storageKey, file.getName(), symbols);
                } catch (Exception e) {
                    log.error("Error parsing file: {}", file.getAbsolutePath(), e);
                }
                return;
            }
        }
    }

    private File resolveLocalPath(String dbPath) {
        // 1. Check for Cloud/Container Override
        String scanRoot = System.getenv("SCAN_ROOT");
        if (scanRoot != null && !scanRoot.isEmpty()) {
            String folderName = new File(dbPath).getName();
            File cloudPath = new File(scanRoot, folderName);
            if (cloudPath.exists())
                return cloudPath;
        }
        return new File(dbPath);
    }

    private void saveResults(Project project, String storageKey, String fileName, List<ParsedSymbol> symbols) {
        // Consistent ID strategy: Use the Storage Key (Project/RelativePath)
        // Check if exists by Project + FilePath
        SourceFile sourceFile = sourceFileRepository.findByProjectAndFilePath(project, storageKey)
                .orElse(new SourceFile());

        sourceFile.setProject(project);
        sourceFile.setFilePath(storageKey); // This is now the MinIO Key or Normalized Path
        sourceFile.setStorageKey(storageKey); // Explicitly set storage key for Retrieval
        sourceFile.setFileName(fileName);
        sourceFile.setExtension(fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.')) : "");
        // We could also set a storage_type = 'MINIO' or 'LOCAL'

        sourceFile = sourceFileRepository.save(sourceFile);

        // Overwrite symbols (Simple strategy: delete existing for this file, then add
        // new)
        // For now, we just append/update. Ideally we clear old symbols first.
        // symbolRepository.deleteBySourceFile(sourceFile);

        int added = 0;
        for (ParsedSymbol dto : symbols) {
            // Deduplication check could go here
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
        log.info("Saved {} symbols for {}", added, storageKey);
    }

    // No changes needed for helper lookup methods if we use the storage key
    // consistently
    private boolean isBinaryFile(File file) {
        try (java.io.InputStream in = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[8000];
            int n = in.read(buffer);
            for (int i = 0; i < n; i++) {
                if (buffer[i] == 0) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            log.warn("Failed to check for binary content, assuming text: {}", file.getName());
            return false;
        }
    }
}
