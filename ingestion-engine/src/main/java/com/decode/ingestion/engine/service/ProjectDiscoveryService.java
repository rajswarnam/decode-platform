package com.decode.ingestion.engine.service;

import com.decode.ingestion.engine.domain.Project;
import com.decode.ingestion.engine.repository.ProjectRepository;
import com.decode.ingestion.service.ExclusionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectDiscoveryService {

    private final ProjectRepository projectRepository;
    private final ExclusionService exclusionService;
    private final SourceFileService sourceFileService;

    public void discoverAndRegisterProjects(String rootPathString, String gitUrl, String contextName) {
        Path rootPath = Paths.get(rootPathString);
        if (!Files.exists(rootPath) || !Files.isDirectory(rootPath)) {
            log.error("Invalid root path: {}", rootPathString);
            return;
        }

        // Track exclusion stats
        AtomicInteger totalDirs = new AtomicInteger(0);
        AtomicInteger excludedDirs = new AtomicInteger(0);

        try {
            Files.walkFileTree(rootPath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    totalDirs.incrementAndGet();

                    // Use ExclusionService for fiscal protection
                    if (exclusionService.shouldExclude(dir)) {
                        excludedDirs.incrementAndGet();
                        log.debug("Excluded directory: {}", dir);
                        return FileVisitResult.SKIP_SUBTREE;
                    }

                    List<String> detectedTech = detectTechStack(dir);
                    if (!detectedTech.isEmpty()) {
                        registerProject(dir, detectedTech, gitUrl, rootPath, contextName);
                        // Prevent monorepo clutter: once a project is found, don't register sub-modules
                        // as separate projects
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            // Log exclusion statistics
            exclusionService.logExclusionStats(totalDirs.get(), excludedDirs.get());

        } catch (IOException e) {
            log.error("Error walking file tree", e);
        }
    }

    private List<String> detectTechStack(Path dir) {
        List<String> tech = new ArrayList<>();
        if (Files.exists(dir.resolve("pom.xml")))
            tech.add("Java (Maven)");
        if (Files.exists(dir.resolve("build.gradle")))
            tech.add("Java (Gradle)");
        if (Files.exists(dir.resolve("package.json")))
            tech.add("Node/React");
        if (Files.exists(dir.resolve("requirements.txt")) || Files.exists(dir.resolve("pyproject.toml")))
            tech.add("Python");
        if (Files.exists(dir.resolve("CMakeLists.txt")) || Files.exists(dir.resolve("Makefile")))
            tech.add("C/C++");

        // Scan for COBOL files (files ending in .cbl, .cob, .cpy)
        try (Stream<Path> stream = Files.list(dir)) {
            boolean hasCobol = stream.anyMatch(p -> {
                String s = p.toString().toLowerCase();
                return s.endsWith(".cbl") || s.endsWith(".cob") || s.endsWith(".cpy");
            });
            if (hasCobol)
                tech.add("COBOL");
        } catch (IOException e) {
            log.warn("Error scanning dir for files: {}", dir, e);
        }
        return tech;
    }

    private void registerProject(Path dir, List<String> techStack, String gitUrl, Path rootPath, String contextName) {
        String projectName = dir.getFileName().toString();

        // If this is the root directory we were searching and we have a context name
        // (zip/repo name), use it
        if (dir.equals(rootPath) && contextName != null && !contextName.isEmpty()) {
            projectName = contextName;
        }
        // If finding multiple projects with same folder name (unlikely in flat
        // structure but possible in monorepo),
        // we might handle it. For now, basic logic.

        Optional<Project> existing = projectRepository.findByName(projectName);
        Project project;
        if (existing.isPresent()) {
            project = existing.get();
            log.info("Project '{}' already registered. Updating metadata and triggering refresh.", projectName);
            project.setBasePath(dir.toAbsolutePath().toString());
            project.setTechStack(techStack);
            project = projectRepository.save(project);
        } else {
            project = new Project();
            project.setName(projectName);
            project.setDomain(System.getenv("PROJECT_DOMAIN") != null ? System.getenv("PROJECT_DOMAIN") : "General");
            project.setBasePath(dir.toAbsolutePath().toString());
            project.setGitUrl(gitUrl);
            project.setTechStack(techStack);
            project.setDescription("Auto-discovered " + String.join(", ", techStack) + " module");

            project = projectRepository.save(project);
            log.info("Registered New Project: {} [{}] in Domain: {}", projectName, String.join(", ", techStack),
                    project.getDomain());
        }

        // Always trigger file ingestion to MinIO and Postgres
        sourceFileService.ingestProjectFiles(project);
    }

    public List<Project> getAllProjects() {
        return projectRepository.findAll();
    }
}
