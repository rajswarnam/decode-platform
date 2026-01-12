package com.decode.ingestion.tool;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pre-Ingestion Analysis Tool
 * Cross-platform (Windows/Linux/macOS) token budget estimator.
 * Scans a directory and predicts TPM savings from exclusion filters.
 */
@Slf4j
public class PreIngestionReport {

    private static final Set<String> BINARY_EXTENSIONS = new HashSet<>(Arrays.asList(
            "class", "jar", "war", "ear", "dll", "exe", "so", "dylib", "bin", "o", "obj", "a", "lib", "out", "pyc",
            "pdb"));

    private static final Set<String> MEDIA_EXTENSIONS = new HashSet<>(Arrays.asList(
            "jpg", "jpeg", "png", "gif", "bmp", "tiff", "svg", "ico", "pdf",
            "doc", "docx", "xls", "xlsx", "ppt", "pptx"));

    private static final Set<String> DATA_EXTENSIONS = new HashSet<>(Arrays.asList(
            "dat", "db", "sqlite", "mdb", "accdb", "dump", "bak", "mdf", "ldf"));

    private static final Set<String> TIBCO_EXTENSIONS = new HashSet<>(Arrays.asList(
            "LOAD", "LNK", "vcrepo", "tra", "projlib", "archive"));

    private static final Set<String> TEMP_EXTENSIONS = new HashSet<>(Arrays.asList(
            "log", "tmp", "temp", "swp", "swo", "bak", "suo", "user"));

    private static final Set<String> EXCLUDED_DIRS = new HashSet<>(Arrays.asList(
            "node_modules", "target", "bin", "obj", "build", "dist", "out",
            ".gradle", ".mvn", "vendor", "packages", ".idea", ".vscode", ".vs",
            ".git", ".svn", "App_Data", "_ReSharper"));

    private final AtomicInteger totalFiles = new AtomicInteger(0);
    private final AtomicInteger excludedFiles = new AtomicInteger(0);
    private final AtomicInteger includedFiles = new AtomicInteger(0);
    private final AtomicLong totalSize = new AtomicLong(0);
    private final AtomicLong excludedSize = new AtomicLong(0);
    private final AtomicLong includedSize = new AtomicLong(0);

    private final Map<String, Integer> excludedTypes = new HashMap<>();
    private final Map<String, Integer> includedTypes = new HashMap<>();

    public static void main(String[] args) {
        String targetDir = args.length > 0 ? args[0] : ".";
        PreIngestionReport report = new PreIngestionReport();
        report.analyze(Paths.get(targetDir));
    }

    public void analyze(Path targetDir) {
        System.out.println("📊 Pre-Ingestion Analysis Report");
        System.out.println("==================================");
        System.out.println("Target Directory: " + targetDir.toAbsolutePath());
        System.out.println();
        System.out.println("🔍 Scanning directory...");

        try {
            Files.walkFileTree(targetDir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    totalFiles.incrementAndGet();
                    long fileSize = attrs.size();
                    totalSize.addAndGet(fileSize);

                    String extension = getFileExtension(file);
                    boolean excluded = shouldExclude(file, extension);

                    if (excluded) {
                        excludedFiles.incrementAndGet();
                        excludedSize.addAndGet(fileSize);
                        excludedTypes.merge(extension, 1, Integer::sum);
                    } else {
                        includedFiles.incrementAndGet();
                        includedSize.addAndGet(fileSize);
                        includedTypes.merge(extension, 1, Integer::sum);
                    }

                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String dirName = dir.getFileName().toString().toLowerCase();
                    if (EXCLUDED_DIRS.contains(dirName)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    log.warn("Could not access: {}", file);
                    return FileVisitResult.CONTINUE;
                }
            });

            printReport();

        } catch (IOException e) {
            log.error("Error scanning directory: {}", targetDir, e);
        }
    }

    private boolean shouldExclude(Path file, String extension) {
        String fileName = file.getFileName().toString().toLowerCase();

        // Check extension-based exclusions
        if (BINARY_EXTENSIONS.contains(extension))
            return true;
        if (MEDIA_EXTENSIONS.contains(extension))
            return true;
        if (DATA_EXTENSIONS.contains(extension))
            return true;
        if (TIBCO_EXTENSIONS.contains(extension.toUpperCase()))
            return true;
        if (TEMP_EXTENSIONS.contains(extension))
            return true;

        // Check system files
        if (fileName.equals(".ds_store") || fileName.equals("thumbs.db"))
            return true;

        // Check package lock files
        if (fileName.equals("package-lock.json") || fileName.equals("yarn.lock"))
            return true;

        return false;
    }

    private String getFileExtension(Path file) {
        String fileName = file.getFileName().toString();
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot > 0 && lastDot < fileName.length() - 1) {
            return fileName.substring(lastDot + 1).toLowerCase();
        }
        return "(no ext)";
    }

    private void printReport() {
        int total = totalFiles.get();
        int excluded = excludedFiles.get();
        int included = includedFiles.get();

        double exclusionRate = total > 0 ? (excluded / (double) total) * 100 : 0;
        double inclusionRate = total > 0 ? (included / (double) total) * 100 : 0;

        // Estimate tokens (rough: 1KB = ~250 tokens)
        long excludedKb = excludedSize.get() / 1024;
        long estimatedTokensSaved = excludedKb * 250;

        DecimalFormat df = new DecimalFormat("#,###");
        DecimalFormat pct = new DecimalFormat("0.0");

        System.out.println();
        System.out.println("📈 Summary Statistics");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("Total Files Scanned:     " + df.format(total));
        System.out.println("Files to INCLUDE:        " + df.format(included) + " (" + pct.format(inclusionRate) + "%)");
        System.out.println("Files to EXCLUDE:        " + df.format(excluded) + " (" + pct.format(exclusionRate) + "%)");
        System.out.println();
        System.out.println("Total Size:              " + formatSize(totalSize.get()));
        System.out.println("Size to Process:         " + formatSize(includedSize.get()));
        System.out.println("Size Excluded:           " + formatSize(excludedSize.get()));
        System.out.println();

        System.out.println("💰 Token Budget Impact");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("Estimated Tokens Saved:  ~" + df.format(estimatedTokensSaved));
        System.out.println("Daily Quota (250k TPM):  250,000");

        if (estimatedTokensSaved > 250_000) {
            double quotaSaved = estimatedTokensSaved / 250_000.0;
            System.out.println("⚠️  Exclusions saved " + pct.format(quotaSaved) + "x your DAILY quota!");
        } else {
            double quotaPct = (estimatedTokensSaved / 250_000.0) * 100;
            System.out.println("✅ Exclusions saved " + pct.format(quotaPct) + "% of daily quota");
        }
        System.out.println();

        System.out.println("📁 Top Excluded File Types");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        excludedTypes.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(10)
                .forEach(e -> System.out.println("  ." + e.getKey() + ": " + e.getValue() + " files"));
        System.out.println();

        System.out.println("✅ Top Included File Types");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        includedTypes.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(10)
                .forEach(e -> System.out.println("  ." + e.getKey() + ": " + e.getValue() + " files"));
        System.out.println();

        System.out.println("🎯 Recommendations");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        if (exclusionRate < 30) {
            System.out.println("⚠️  Low exclusion rate. Consider reviewing .decodeignore patterns.");
        } else if (exclusionRate > 80) {
            System.out.println("✅ Excellent noise filtering! Most files are excluded.");
        } else {
            System.out.println("✅ Good balance. Exclusion filters are working effectively.");
        }

        if (estimatedTokensSaved > 500_000) {
            System.out.println("🚨 CRITICAL: This directory would exceed 2x daily quota without filters!");
            System.out.println("   Action: Ensure .decodeignore is strictly enforced.");
        }

        System.out.println();
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("✓ Pre-Ingestion Report Complete");
        System.out.println();
        System.out.println("Next Steps:");
        System.out.println("  1. Review excluded file types above");
        System.out.println("  2. Adjust .decodeignore if needed");
        System.out.println("  3. Run ingestion with confidence!");
    }

    private String formatSize(long bytes) {
        if (bytes >= 1_073_741_824) {
            return String.format("%.2f GB", bytes / 1_073_741_824.0);
        } else if (bytes >= 1_048_576) {
            return String.format("%.2f MB", bytes / 1_048_576.0);
        } else if (bytes >= 1024) {
            return String.format("%.2f KB", bytes / 1024.0);
        } else {
            return bytes + " B";
        }
    }
}
