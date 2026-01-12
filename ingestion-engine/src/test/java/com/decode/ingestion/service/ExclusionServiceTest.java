package com.decode.ingestion.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ExclusionServiceTest {

    private ExclusionService exclusionService;

    @BeforeEach
    void setUp() {
        exclusionService = new ExclusionService();
    }

    @Test
    void shouldExcludeBinaryFiles(@TempDir Path tempDir) throws IOException {
        Path classFile = tempDir.resolve("Test.class");
        Files.createFile(classFile);

        assertTrue(exclusionService.shouldExclude(classFile),
                ".class files should be excluded");
    }

    @Test
    void shouldExcludeMediaFiles(@TempDir Path tempDir) throws IOException {
        Path jpgFile = tempDir.resolve("image.jpg");
        Files.createFile(jpgFile);

        assertTrue(exclusionService.shouldExclude(jpgFile),
                ".jpg files should be excluded");
    }

    @Test
    void shouldExcludeTibcoFiles(@TempDir Path tempDir) throws IOException {
        Path vcrepoFile = tempDir.resolve("config.vcrepo");
        Files.createFile(vcrepoFile);

        assertTrue(exclusionService.shouldExclude(vcrepoFile),
                ".vcrepo files should be excluded");
    }

    @Test
    void shouldExcludeDataFiles(@TempDir Path tempDir) throws IOException {
        Path datFile = tempDir.resolve("transactions.dat");
        Files.createFile(datFile);

        assertTrue(exclusionService.shouldExclude(datFile),
                ".dat files should be excluded (TPM risk)");
    }

    @Test
    void shouldExcludeBuildDirectories(@TempDir Path tempDir) throws IOException {
        Path nodeModules = tempDir.resolve("node_modules");
        Files.createDirectory(nodeModules);

        assertTrue(exclusionService.shouldExclude(nodeModules),
                "node_modules/ should be excluded");
    }

    @Test
    void shouldIncludeSourceCodeFiles(@TempDir Path tempDir) throws IOException {
        Path javaFile = tempDir.resolve("Application.java");
        Files.createFile(javaFile);

        assertFalse(exclusionService.shouldExclude(javaFile),
                ".java files should be included");
    }

    @Test
    void shouldIncludeCobolFiles(@TempDir Path tempDir) throws IOException {
        Path cobolFile = tempDir.resolve("CUSTOMER.cbl");
        Files.createFile(cobolFile);

        assertFalse(exclusionService.shouldExclude(cobolFile),
                ".cbl files should be included");
    }

    @Test
    void shouldIncludeConfigFiles(@TempDir Path tempDir) throws IOException {
        Path xmlFile = tempDir.resolve("pom.xml");
        Files.createFile(xmlFile);

        assertFalse(exclusionService.shouldExclude(xmlFile),
                ".xml files should be included");
    }

    @Test
    void shouldExcludeLargeNonSourceFiles(@TempDir Path tempDir) throws IOException {
        Path largeLog = tempDir.resolve("huge.log");
        // Create a 2MB file
        byte[] data = new byte[2 * 1024 * 1024];
        Files.write(largeLog, data);

        assertTrue(exclusionService.shouldExclude(largeLog),
                "Large log files should be excluded");
    }

    @Test
    void shouldIncludeLargeSourceFiles(@TempDir Path tempDir) throws IOException {
        Path largeJava = tempDir.resolve("Generated.java");
        // Create a 2MB Java file
        byte[] data = new byte[2 * 1024 * 1024];
        Files.write(largeJava, data);

        assertFalse(exclusionService.shouldExclude(largeJava),
                "Large source files should be included");
    }

    @Test
    void shouldExcludeSystemFiles(@TempDir Path tempDir) throws IOException {
        Path dsStore = tempDir.resolve(".DS_Store");
        Files.createFile(dsStore);

        assertTrue(exclusionService.shouldExclude(dsStore),
                ".DS_Store should be excluded");
    }

    @Test
    void shouldLogExclusionStats() {
        // This test verifies the method doesn't throw exceptions
        assertDoesNotThrow(() -> exclusionService.logExclusionStats(100, 75));
    }
}
