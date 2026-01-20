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
    private final IngestionEventService ingestionEventService;

    public void discoverAndRegisterProjects(String rootPathString, String gitUrl, String contextName, String targetDomain) {
        Path rootPath = Paths.get(rootPathString);
        if (!Files.exists(rootPath) || !Files.isDirectory(rootPath)) {
            log.error("Invalid root path: {}", rootPathString);
            ingestionEventService.sendEvent("ERROR: Invalid root path: " + rootPathString);
            return;
        }

        ingestionEventService.sendEvent("🔍 Starting project discovery in: " + contextName);
        
        // Track exclusion stats
        AtomicInteger totalDirs = new AtomicInteger(0);
        AtomicInteger excludedDirs = new AtomicInteger(0);
        AtomicInteger projectsFound = new AtomicInteger(0);

        try {
            Files.walkFileTree(rootPath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    totalDirs.incrementAndGet();
                    
                    // Send progress every 100 directories
                    if (totalDirs.get() % 100 == 0) {
                        ingestionEventService.sendEvent(String.format("📂 Scanned %d directories, found %d projects...", 
                            totalDirs.get(), projectsFound.get()));
                    }

                    // Use ExclusionService for fiscal protection
                    if (exclusionService.shouldExclude(dir)) {
                        excludedDirs.incrementAndGet();
                        log.debug("Excluded directory: {}", dir);
                        return FileVisitResult.SKIP_SUBTREE;
                    }

                    // Filter out non-relevant test/e2e folders
                    String pathStr = dir.toString();
                    if (pathStr.contains("/e2e/") || pathStr.endsWith("/e2e") || 
                        pathStr.contains("/test/") || pathStr.endsWith("/test")) {
                        log.debug("Skipping test/e2e directory: {}", dir);
                        return FileVisitResult.SKIP_SUBTREE;
                    }

                    List<String> detectedTech = detectTechStack(dir);
                    if (!detectedTech.isEmpty()) {
                        projectsFound.incrementAndGet();
                        ingestionEventService.sendEvent("✅ Found project: " + dir.getFileName() + " [" + String.join(", ", detectedTech) + "]");
                        registerProject(dir, detectedTech, gitUrl, rootPath, contextName, targetDomain);
                        // Prevent monorepo clutter: once a project is found, don't register sub-modules
                        // as separate projects
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            // Log exclusion statistics
            exclusionService.logExclusionStats(totalDirs.get(), excludedDirs.get());
            
            ingestionEventService.sendEvent(String.format("✅ Discovery complete: %d projects found, %d directories scanned, %d excluded", 
                projectsFound.get(), totalDirs.get(), excludedDirs.get()));
            
            // Mark the placeholder parent project as completed if it exists
            updateProjectStatus(contextName, "DISCOVERY_COMPLETED");

            // Log exclusion statistics
            exclusionService.logExclusionStats(totalDirs.get(), excludedDirs.get());

        } catch (IOException e) {
            log.error("Error walking file tree", e);
            ingestionEventService.sendEvent("ERROR: " + e.getMessage());
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

        // Scan for source files to detect tech stack (even without build files)
        try (Stream<Path> stream = Files.list(dir)) {
            boolean hasCobol = false;
            boolean hasC = false;
            boolean hasCpp = false;
            
            for (Path p : stream.collect(java.util.stream.Collectors.toList())) {
                String s = p.toString().toLowerCase();
                
                // COBOL detection
                if (s.endsWith(".cbl") || s.endsWith(".cob") || s.endsWith(".cpy")) {
                    hasCobol = true;
                }
                
                // C/C++ detection by source files (not just build files)
                if (s.endsWith(".c")) {
                    hasC = true;
                }
                if (s.endsWith(".cpp") || s.endsWith(".cc") || s.endsWith(".cxx") || s.endsWith(".c++")) {
                    hasCpp = true;
                }
                if (s.endsWith(".h") || s.endsWith(".hpp") || s.endsWith(".hxx")) {
                    // Header files could be C or C++, but if we see .c files, it's C
                    // If we see .cpp files, it's C++
                    // If we only see headers, assume C++ (more common)
                    if (!hasC && !hasCpp) {
                        hasCpp = true; // Default to C++ if only headers
                    }
                }
            }
            
            if (hasCobol)
                tech.add("COBOL");
            
            // Add C/C++ if we found source files (even without CMakeLists.txt or Makefile)
            if (hasC || hasCpp) {
                if (!tech.contains("C/C++")) {
                    tech.add("C/C++");
                }
            }
        } catch (IOException e) {
            log.warn("Error scanning dir for files: {}", dir, e);
        }
        return tech;
    }

    private void registerProject(Path dir, List<String> techStack, String gitUrl, Path rootPath, String contextName, String targetDomain) {
        String projectName = dir.getFileName().toString();

        // If this is the root directory we were searching and we have a context name
        // (zip/repo name), use it. Or namespace sub-projects.
        if (contextName != null && !contextName.isEmpty()) {
            if (dir.equals(rootPath)) {
                projectName = contextName;
            } else {
                String relativePath = rootPath.relativize(dir).toString();
                projectName = contextName + "/" + relativePath;
            }
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
            
            String domain = (targetDomain != null && !targetDomain.isEmpty()) ? targetDomain : 
                           (System.getenv("PROJECT_DOMAIN") != null ? System.getenv("PROJECT_DOMAIN") : "General");
            project.setDomain(domain);
            
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

    public void registerPendingProject(String repoName, String gitUrl, String basePath, String targetDomain) {
        Project p;
        Optional<Project> existing = projectRepository.findByName(repoName);
        if (existing.isPresent()) {
            p = existing.get();
            p.setStatus("PENDING_CLONE");
            p.setGitUrl(gitUrl);
            p.setBasePath(basePath);
            p.setIngestionProgress(0);
            projectRepository.save(p);
            log.info("Updated existing project {} status to PENDING_CLONE", repoName);
        } else {
            p = new Project();
            p.setName(repoName);
            p.setGitUrl(gitUrl);
            p.setBasePath(basePath);
            
            String domain = (targetDomain != null && !targetDomain.isEmpty()) ? targetDomain : 
                           (System.getenv("PROJECT_DOMAIN") != null ? System.getenv("PROJECT_DOMAIN") : "General");
            p.setDomain(domain);
            
            p.setStatus("PENDING_CLONE");
            p.setIngestionProgress(0);
            projectRepository.save(p);
            log.info("Registered new pending project: {}", repoName);
        }
        ingestionEventService.sendProgress(p);
    }

    public void updateProjectStatus(String repoName, String status) {
        Optional<Project> existing = projectRepository.findByName(repoName);
        if (existing.isPresent()) {
            Project p = existing.get();
            p.setStatus(status);
            projectRepository.save(p);
            ingestionEventService.sendProgress(p);
            log.info("Updated project {} status to {}", repoName, status);
        }
    }

    public List<Project> getAllProjects() {
        return projectRepository.findAll();
    }

    @org.springframework.transaction.annotation.Transactional
    public void cleanupDuplicates() {
        List<Project> all = projectRepository.findAll();
        for (Project p : all) {
            // Remove 'Automated Scan' duplicates only.
            // CAUTION: Do not delete common module names like 'admin' or 'camel' as they are valid parts of the repo.
            if (p.getName().startsWith("Automated Scan")) {
                
                log.info("Refinery: Removing temporary scan project: {}", p.getName());
                // Force clean external references to avoid FK violation
                projectRepository.deleteAclfMappings(p.getId());
                projectRepository.delete(p);
            }
        }
    }

    @org.springframework.transaction.annotation.Transactional
    public void deleteAllProjects() {
        List<Project> all = projectRepository.findAll();
        for (Project p : all) {
            log.info("Manual Cleanup: Deleting project: {}", p.getName());
            projectRepository.deleteAclfMappings(p.getId());
            projectRepository.delete(p);
        }
    }
}
