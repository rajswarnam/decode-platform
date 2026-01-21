package com.decode.context.orchestrator.controller;

import com.decode.context.orchestrator.domain.Project;
import com.decode.context.orchestrator.domain.AclfMapping;
import com.decode.context.orchestrator.domain.DependencyLineage;
import com.decode.context.orchestrator.repository.ProjectRepository;
import com.decode.context.orchestrator.repository.AclfMappingRepository;
import com.decode.context.orchestrator.repository.SymbolRepository;
import com.decode.context.orchestrator.service.SemanticExplorerService;
import com.decode.context.orchestrator.service.LineageDiscoveryService;
import com.decode.context.orchestrator.service.BlueprintService;
import com.decode.context.orchestrator.service.StitchService;
import com.decode.context.orchestrator.domain.Symbol;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import com.decode.context.orchestrator.service.ChangeDetectionService;
import com.decode.context.orchestrator.service.ContextRetrievalService;
import com.decode.context.orchestrator.repository.BlueprintRefinementRepository;
import com.decode.context.orchestrator.domain.BlueprintRefinement;
import com.decode.context.orchestrator.service.SnippetService;

@RestController
@RequestMapping("/api/v1/explore")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@Slf4j
public class SemanticExplorerController {

        private final ProjectRepository projectRepository;
        private final AclfMappingRepository aclfMappingRepository;
        private final SymbolRepository symbolRepository;
        private final SemanticExplorerService explorerService;
        private final LineageDiscoveryService lineageDiscoveryService;
        private final MinioClient minioClient;
        private final ChangeDetectionService changeDetectionService;
        private final ContextRetrievalService contextRetrievalService;
        private final BlueprintRefinementRepository blueprintRefinementRepository;
        private final BlueprintService blueprintService; // Restored as requested
        private final StitchService stitchService;
        
        @org.springframework.beans.factory.annotation.Value("${minio.bucket}")
        private String bucketName;

        @GetMapping("/blueprint")
        public ResponseEntity<String> generateBlueprint(@RequestParam String projectName) {
                try {
                        String report = blueprintService.generateFusionArgoBlueprint(projectName);
                        return ResponseEntity.ok(report);
                } catch (Exception e) {
                        log.error("Error generating blueprint", e);
                        return ResponseEntity.status(500).body("Error generating blueprint: " + e.getMessage());
                }
        }

        @GetMapping("/projects")
        public ResponseEntity<List<Project>> getProjects() {
                return ResponseEntity.ok(projectRepository.findAll());
        }

        @PostMapping(value = "/query", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        public org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody query(
                        @RequestBody Map<String, Object> request) {
                String query = (String) request.get("query");
                String domain = (String) request.getOrDefault("domain", "General");
                
                // Support multiple projects: if "projects" array is provided, use it
                // Otherwise, check if domain is comma-separated list
                List<String> projectNamesList = new ArrayList<>();
                List<UUID> projectIdsList = new ArrayList<>();
                
                if (request.containsKey("projects") && request.get("projects") instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<String> projectsList = (List<String>) request.get("projects");
                    projectNamesList.addAll(projectsList);
                    
                    // Convert project names to IDs for vector search filtering
                    for (String projectName : projectNamesList) {
                        projectRepository.findByName(projectName).ifPresent(p -> {
                            projectIdsList.add(p.getId());
                        });
                    }
                    log.info("Multi-project query: {} projects selected ({} IDs resolved)", projectNamesList.size(), projectIdsList.size());
                } else if (domain != null && domain.contains(",")) {
                    // Comma-separated project names
                    projectNamesList = new ArrayList<>(Arrays.asList(domain.split(",")));
                    for (String projectName : projectNamesList) {
                        projectRepository.findByName(projectName.trim()).ifPresent(p -> {
                            projectIdsList.add(p.getId());
                        });
                    }
                    log.info("Multi-project query (comma-separated): {} projects ({} IDs resolved)", projectNamesList.size(), projectIdsList.size());
                } else {
                    // Single project/domain
                    projectNamesList.add(domain);
                    projectRepository.findByName(domain).ifPresent(p -> {
                        projectIdsList.add(p.getId());
                    });
                }

                // Make final copies for use in lambda
                final List<String> finalProjectNames = new ArrayList<>(projectNamesList);
                final List<UUID> finalProjectIds = new ArrayList<>(projectIdsList);
                final String finalDomain = domain;

                return outputStream -> {
                        java.io.PrintWriter writer = new java.io.PrintWriter(new java.io.OutputStreamWriter(
                                        outputStream, java.nio.charset.StandardCharsets.UTF_8));

                        try {
                                // Pass first project name for backward compatibility, but also pass full list and IDs
                                String primaryDomain = finalProjectNames.isEmpty() ? finalDomain : finalProjectNames.get(0);
                                explorerService.exploreStream(query, primaryDomain, finalProjectNames, finalProjectIds,
                                                progress -> {
                                                        writer.write("event: progress\n");
                                                        writer.write("data: " + progress + "\n\n");
                                                        writer.flush();
                                                },
                                                answerChunk -> {
                                                        // Send plain text, but escape newlines to preserve formatting
                                                        try {
                                                                // Replace actual newlines with escaped version for SSE
                                                                String escaped = answerChunk.replace("\n", "\\n");
                                                                writer.write("event: answer\n");
                                                                writer.write("data: " + escaped + "\n\n");
                                                                writer.flush();
                                                        } catch (Exception e) {
                                                                log.error("Error sending chunk", e);
                                                        }
                                                });
                        } catch (Exception e) {
                                log.error("Error in streaming reasoning", e);
                                try {
                                        writer.write("event: error\n");
                                        writer.write("data: Connection error: " + e.getMessage() + "\n\n");
                                        writer.flush();
                                } catch (Exception flushError) {
                                        log.error("Error sending error event", flushError);
                                }
                        } finally {
                                try {
                                        writer.write("event: complete\n");
                                        writer.write("data: Stream closed\n\n");
                                        writer.flush();
                                } catch (Exception e) {
                                        log.debug("Error closing stream", e);
                                }
                                writer.close();
                        }
                };
        }

        @PostMapping("/save-blueprint")
        public ResponseEntity<Map<String, String>> saveBlueprint(@RequestBody Map<String, String> request) {
                try {
                        String content = request.get("content");
                        String query = request.getOrDefault("query", "analysis");
                        String projectName = request.getOrDefault("project", "general");
                        String title = request.getOrDefault("title", query);
                        String category = request.getOrDefault("category", "general");
                        String tags = request.getOrDefault("tags", "");

                        // Generate filename with timestamp
                        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
                        String sanitizedTitle = title.replaceAll("[^a-zA-Z0-9\\s]", "")
                                        .trim()
                                        .replaceAll("\\s+", "-")
                                        .toLowerCase();

                        // Limit length after sanitization
                        if (sanitizedTitle.length() > 50) {
                                sanitizedTitle = sanitizedTitle.substring(0, 50);
                        }

                        String filename = String.format("%s_%s.md", sanitizedTitle, timestamp);

                        // MinIO path: blueprints/{project}/{category}/{filename}
                        String objectKey = String.format("blueprints/%s/%s/%s", projectName, category, filename);

                        // Upload to MinIO
                        byte[] contentBytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        java.io.ByteArrayInputStream stream = new java.io.ByteArrayInputStream(contentBytes);

                        // Add metadata as tags
                        Map<String, String> userMetadata = new HashMap<>();
                        userMetadata.put("title", title);
                        userMetadata.put("category", category);
                        userMetadata.put("tags", tags);
                        userMetadata.put("query", query);

                        PutObjectArgs putArgs = PutObjectArgs.builder()
                                        .bucket(bucketName)
                                        .object(objectKey)
                                        .stream(stream, contentBytes.length, -1)
                                        .contentType("text/markdown")
                                        .userMetadata(userMetadata)
                                        .build();

                        minioClient.putObject(putArgs);

                        log.info("Blueprint saved to MinIO: {} (title: {}, category: {})", objectKey, title, category);

                        Map<String, String> response = new HashMap<>();
                        response.put("status", "success");
                        response.put("filename", filename);
                        response.put("path", objectKey);
                        response.put("project", projectName);
                        response.put("title", title);
                        response.put("category", category);

                        return ResponseEntity.ok(response);
                } catch (Exception e) {
                        log.error("Error saving blueprint to MinIO", e);
                        Map<String, String> response = new HashMap<>();
                        response.put("status", "error");
                        response.put("message", e.getMessage());
                        return ResponseEntity.status(500).body(response);
                }
        }

        @GetMapping("/list-blueprints")
        public ResponseEntity<List<Map<String, Object>>> listBlueprints(
                        @RequestParam(defaultValue = "piggymetrics") String project) {
                try {
                        String prefix = String.format("blueprints/%s/", project);
                        List<Map<String, Object>> blueprints = new java.util.ArrayList<>();

                        io.minio.ListObjectsArgs listArgs = io.minio.ListObjectsArgs.builder()
                                        .bucket(bucketName)
                                        .prefix(prefix)
                                        .recursive(true) // Recursively list all files in subfolders
                                        .build();

                        for (io.minio.Result<io.minio.messages.Item> result : minioClient.listObjects(listArgs)) {
                                io.minio.messages.Item item = result.get();

                                // Skip directories and only include .md files
                                if (!item.isDir() && item.objectName().endsWith(".md")) {
                                        Map<String, Object> blueprint = new HashMap<>();

                                        // Extract just the filename from the full path
                                        String fullPath = item.objectName();
                                        String filename = fullPath.substring(fullPath.lastIndexOf('/') + 1);

                                        blueprint.put("filename", filename);
                                        blueprint.put("path", item.objectName());
                                        blueprint.put("size", item.size());
                                        blueprint.put("lastModified", item.lastModified());
                                        blueprints.add(blueprint);
                                }
                        }

                        // Sort by last modified date (newest first)
                        blueprints.sort((a, b) -> {
                                java.time.ZonedDateTime dateA = (java.time.ZonedDateTime) a.get("lastModified");
                                java.time.ZonedDateTime dateB = (java.time.ZonedDateTime) b.get("lastModified");
                                return dateB.compareTo(dateA);
                        });

                        return ResponseEntity.ok(blueprints);
                } catch (Exception e) {
                        log.error("Error listing blueprints from MinIO", e);
                        return ResponseEntity.status(500).body(new java.util.ArrayList<>());
                }
        }

        @GetMapping("/get-blueprint")
        public ResponseEntity<String> getBlueprint(@RequestParam String path) {
                try {
                        io.minio.GetObjectArgs getArgs = io.minio.GetObjectArgs.builder()
                                        .bucket(bucketName)
                                        .object(path)
                                        .build();

                        try (java.io.InputStream stream = minioClient.getObject(getArgs)) {
                                String content = new String(stream.readAllBytes(),
                                                java.nio.charset.StandardCharsets.UTF_8);
                                return ResponseEntity.ok(content);
                        }
                } catch (Exception e) {
                        log.error("Error fetching blueprint from MinIO: {}", path, e);
                        return ResponseEntity.status(500).body("Error loading blueprint: " + e.getMessage());
                }
        }

        @GetMapping("/metrics")
        public ResponseEntity<Map<String, Object>> getProjectMetrics(@RequestParam("project") String projectName) {
                Project project = projectRepository.findByName(projectName)
                                .orElseThrow(() -> new RuntimeException("Project not found: " + projectName));

                Double avgConfidence = aclfMappingRepository.calculateAverageConfidenceByProject(project.getId());
                long totalMappings = aclfMappingRepository.countByProject_Id(project.getId());
                long ambiguousCount = aclfMappingRepository.countByProject_IdAndMappingStrategy(project.getId(), "AMBIGUOUS_MATCH");
                
                // Fallback for Modern Projects (No ACLF)
                if (totalMappings == 0) {
                     // If no legacy mappings, count symbols as "Rules" logic nodes
                     // We need a repository method countByProject, assuming it exists or we use a heuristic
                     // For now, let's return a distinct flag
                }

                Map<String, Object> metrics = new HashMap<>();
                metrics.put("totalTrustScore", avgConfidence != null ? (int) (avgConfidence * 100) : 100); // Default to 100 for modern
                metrics.put("ambiguityCount", ambiguousCount);
                // Rename for frontend flexibility
                metrics.put("mappedRulesCount", totalMappings > 0 ? totalMappings : 0); 
                metrics.put("isModern", totalMappings == 0);
                metrics.put("lastScoreRefresh", LocalDateTime.now().toString());

                return ResponseEntity.ok(metrics);
        }

        @GetMapping("/mappings")
        public ResponseEntity<List<AclfMapping>> getMappings(@RequestParam("project") String projectName) {
                return ResponseEntity.ok(aclfMappingRepository.findByProject_Name(projectName));
        }

        @GetMapping("/ambiguities")
        public ResponseEntity<List<Map<String, Object>>> getAmbiguities(@RequestParam("project") String projectName) {
                // Return items with low confidence or specific strategy
                List<AclfMapping> all = aclfMappingRepository.findByProject_Name(projectName);
                List<Map<String, Object>> ambiguities = new java.util.ArrayList<>();

                for (AclfMapping m : all) {
                        if (m.getConfidenceScore() < 0.8 || "AMBIGUOUS_MATCH".equals(m.getMappingStrategy())) {
                                Map<String, Object> ambiguity = new HashMap<>();
                                ambiguity.put("id", m.getId());
                                ambiguity.put("tag", m.getAttributeTag());
                                ambiguity.put("mappingStrategy", m.getMappingStrategy());
                                ambiguity.put("confidenceScore", m.getConfidenceScore());
                                ambiguity.put("description", "Low confidence mapping for " + m.getAttributeTag());
                                ambiguity.put("timestamp", m.getCreatedAt());

                                List<Map<String, Object>> candidates = new ArrayList<>();
                                for (Symbol symbol : findCandidateSymbols(projectName, m.getAttributeTag())) {
                                        Map<String, Object> candidate = new HashMap<>();
                                        candidate.put("id", symbol.getId());
                                        candidate.put("name", symbol.getName());
                                        candidate.put("path",
                                                        symbol.getSourceFile() != null ? symbol.getSourceFile().getFilePath() : "unknown");
                                        candidate.put("type", symbol.getCategory());
                                        candidate.put("affinity", computeAffinity(symbol.getName(), m.getAttributeTag()));
                                        candidates.add(candidate);
                                }
                                ambiguity.put("candidates", candidates);
                                ambiguities.add(ambiguity);
                        }
                }

                return ResponseEntity.ok(ambiguities);
        }

        @PostMapping("/resolve-ambiguity")
        public ResponseEntity<Map<String, Object>> resolveAmbiguity(@RequestBody Map<String, String> request) {
                String mappingId = request.get("mappingId");
                String selectedSymbolId = request.getOrDefault("selectedSymbolId", "");

                Map<String, Object> response = new HashMap<>();
                if (mappingId == null || mappingId.isBlank()) {
                        response.put("status", "error");
                        response.put("message", "mappingId is required");
                        return ResponseEntity.badRequest().body(response);
                }

                try {
                        boolean success = stitchService.stitch(UUID.fromString(mappingId), selectedSymbolId);
                        if (!success) {
                                response.put("status", "error");
                                response.put("message", "Unable to resolve ambiguity");
                                return ResponseEntity.status(404).body(response);
                        }
                        response.put("status", "success");
                        response.put("mappingId", mappingId);
                        return ResponseEntity.ok(response);
                } catch (IllegalArgumentException e) {
                        response.put("status", "error");
                        response.put("message", "Invalid UUID for mappingId");
                        return ResponseEntity.badRequest().body(response);
                }
        }

        @PostMapping("/refine-blueprint")
        public ResponseEntity<Map<String, Object>> refineBlueprint(@RequestBody Map<String, Object> request) {
                try {
                        String blueprintPath = getStringParam(request, "blueprintPath", "");
                        String refinementPrompt = getStringParam(request, "refinementPrompt", "");
                        String project = getStringParam(request, "project", "piggymetrics");
                        boolean updateExisting = getBooleanParam(request, "updateExisting", false);

                        log.info("Refining blueprint: {} with prompt: {}", blueprintPath, refinementPrompt);

                        // STEP 1: Load existing blueprint from MinIO
                        io.minio.GetObjectArgs getArgs = io.minio.GetObjectArgs.builder()
                                        .bucket(bucketName)
                                        .object(blueprintPath)
                                        .build();

                        String existingBlueprint;
                        Map<String, String> metadata = new HashMap<>();

                        try (java.io.InputStream stream = minioClient.getObject(getArgs)) {
                                existingBlueprint = new String(stream.readAllBytes(),
                                                java.nio.charset.StandardCharsets.UTF_8);

                                // Get metadata from MinIO object
                                io.minio.StatObjectArgs statArgs = io.minio.StatObjectArgs.builder()
                                                .bucket(bucketName)
                                                .object(blueprintPath)
                                                .build();
                                io.minio.StatObjectResponse stat = minioClient.statObject(statArgs);
                                metadata.putAll(stat.userMetadata());
                        }

                        // STEP 2: Detect code changes since blueprint creation
                        String blueprintTimestamp = metadata.getOrDefault("createdAt", LocalDateTime.now().toString());
                        LocalDateTime blueprintTime = LocalDateTime.parse(blueprintTimestamp.substring(0, 19));
                        String originalQuery = metadata.getOrDefault("query", "");

                        ChangeDetectionService.ChangeReport changes = changeDetectionService.detectChanges(
                                        project, originalQuery, blueprintTime);

                        // STEP 3: Fetch enhanced context
                        ContextRetrievalService.EnhancedContext context = contextRetrievalService.fetchContext(
                                        project, originalQuery, refinementPrompt, changes);

                        // STEP 4: Build refinement prompt for LLM
                        String llmPrompt = buildRefinementPrompt(existingBlueprint, refinementPrompt, context, changes);

                        // STEP 5: Call LLM to refine blueprint
                        String refinedBlueprint = explorerService.callLlmForRefinement(llmPrompt);

                        // STEP 6: Save refined blueprint
                        int currentVersion = Integer.parseInt(metadata.getOrDefault("version", "1"));
                        int newVersion = updateExisting ? currentVersion : currentVersion + 1;

                        String newPath;
                        if (updateExisting) {
                                newPath = blueprintPath;
                        } else {
                                // Generate new path with version
                                String basePath = blueprintPath.replaceAll("_v\\d+_", "_")
                                                .replaceAll("_\\d{8}_\\d{6}\\.md$", "");
                                String timestamp = LocalDateTime.now()
                                                .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
                                newPath = basePath + "_v" + newVersion + "_" + timestamp + ".md";
                        }

                        Map<String, String> newMetadata = new HashMap<>(metadata);
                        newMetadata.put("version", String.valueOf(newVersion));
                        newMetadata.put("parentBlueprintId", blueprintPath);
                        newMetadata.put("refinementPrompt", refinementPrompt);
                        newMetadata.put("codeChangesDetected", String.valueOf(changes.hasChanges()));
                        newMetadata.put("createdAt", LocalDateTime.now().toString());

                        byte[] contentBytes = refinedBlueprint.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        java.io.ByteArrayInputStream contentStream = new java.io.ByteArrayInputStream(contentBytes);

                        PutObjectArgs putArgs = PutObjectArgs.builder()
                                        .bucket(bucketName)
                                        .object(newPath)
                                        .stream(contentStream, contentBytes.length, -1)
                                        .contentType("text/markdown")
                                        .userMetadata(newMetadata)
                                        .build();

                        minioClient.putObject(putArgs);

                        // STEP 7: Record refinement history
                        BlueprintRefinement refinement = new BlueprintRefinement(
                                        newPath, blueprintPath, project, newVersion, refinementPrompt);
                        refinement.setCodeChangesDetected(changes.hasChanges());
                        refinement.setNewSymbolsCount(changes.getNewSymbolsCount());
                        refinement.setModifiedSymbolsCount(changes.getModifiedSymbolsCount());
                        refinement.setDeletedSymbolsCount(changes.getDeletedSymbolsCount());
                        refinement.setChangeSummary(changes.getSummary());

                        blueprintRefinementRepository.save(refinement);

                        log.info("Blueprint refined successfully: {}", newPath);

                        // Build response
                        Map<String, Object> response = new HashMap<>();
                        response.put("status", "success");
                        response.put("newBlueprintPath", newPath);
                        response.put("version", newVersion);

                        Map<String, Object> codeChanges = new HashMap<>();
                        codeChanges.put("detected", changes.hasChanges());
                        codeChanges.put("newSymbols", changes.getNewSymbolsCount());
                        codeChanges.put("modifiedSymbols", changes.getModifiedSymbolsCount());
                        codeChanges.put("deletedSymbols", changes.getDeletedSymbolsCount());
                        codeChanges.put("summary", changes.getSummary());
                        response.put("codeChanges", codeChanges);

                        return ResponseEntity.ok(response);

                } catch (Exception e) {
                        log.error("Error refining blueprint", e);
                        Map<String, Object> response = new HashMap<>();
                        response.put("status", "error");
                        response.put("message", e.getMessage());
                        return ResponseEntity.status(500).body(response);
                }
        }

        private String getStringParam(Map<String, Object> request, String key, String defaultValue) {
                Object value = request.get(key);
                return value != null ? String.valueOf(value) : defaultValue;
        }

        private boolean getBooleanParam(Map<String, Object> request, String key, boolean defaultValue) {
                Object value = request.get(key);
                if (value == null) {
                        return defaultValue;
                }
                if (value instanceof Boolean) {
                        return (Boolean) value;
                }
                return Boolean.parseBoolean(String.valueOf(value));
        }

        private List<Symbol> findCandidateSymbols(String projectName, String tag) {
                if (tag == null || tag.isBlank()) {
                        return List.of();
                }

                String normalized = tag.replaceAll("[^A-Za-z0-9_]", " ");
                String[] tokens = normalized.split("[_\\s]+");
                Set<Symbol> results = new LinkedHashSet<>();

                for (String token : tokens) {
                        if (token.length() < 3) {
                                continue;
                        }
                        results.addAll(symbolRepository
                                        .findTop10ByNameContainingIgnoreCaseAndSourceFile_Project_Name(token, projectName));
                        if (results.size() >= 10) {
                                break;
                        }
                }

                if (results.isEmpty()) {
                        results.addAll(symbolRepository
                                        .findTop10ByNameContainingIgnoreCaseAndSourceFile_Project_Name(tag, projectName));
                }

                List<Symbol> list = new ArrayList<>(results);
                return list.size() > 10 ? list.subList(0, 10) : list;
        }

        private double computeAffinity(String symbolName, String tag) {
                if (symbolName == null || tag == null) {
                        return 0.6;
                }
                String s = symbolName.toLowerCase();
                String t = tag.replace("_", "").toLowerCase();
                if (s.equals(t)) {
                        return 1.0;
                }
                if (s.contains(t) || t.contains(s)) {
                        return 0.85;
                }
                return 0.65;
        }

        private final SnippetService snippetService;

        @GetMapping("/snippet")
        public ResponseEntity<Map<String, String>> getSnippet(
                        @RequestParam String storageKey,
                        @RequestParam int startLine,
                        @RequestParam int endLine) {

                String code = snippetService.fetchSnippet(storageKey, startLine, endLine);
                return ResponseEntity.ok(Map.of("code", code));
        }

        @GetMapping("/lineage/semantic")
        public ResponseEntity<Map<String, Object>> getSemanticLineage(@RequestParam String projectName) {
                // Return a simplified graph structure for the frontend
                List<DependencyLineage> lineages = lineageDiscoveryService.getLineageForProject(projectName);

                List<Map<String, Object>> nodes = new java.util.ArrayList<>();
                List<Map<String, Object>> links = new java.util.ArrayList<>();

                // Add the main project node
                nodes.add(Map.of("id", projectName, "type", "service", "label", projectName));

                for (DependencyLineage l : lineages) {
                        String target = l.getTargetServiceName();
                        if (target == null || target.isBlank())
                                continue;

                        // Add target node
                        nodes.add(Map.of("id", target, "type", "dependency", "label", target));

                        // Add link
                        links.add(Map.of(
                                        "source", projectName,
                                        "target", target,
                                        "label", l.getProtocol(),
                                        "confidence", l.getConfidenceScore()));
                }

                return ResponseEntity.ok(Map.of("nodes", nodes, "links", links));
        }

        private String buildRefinementPrompt(
                        String existingBlueprint,
                        String refinementPrompt,
                        ContextRetrievalService.EnhancedContext context,
                        ChangeDetectionService.ChangeReport changes) {

                return String.format("""
                                You are refining an existing technical blueprint for a software system.

                                ## ORIGINAL BLUEPRINT
                                %s

                                ## CODE CHANGES SINCE BLUEPRINT CREATION
                                %s

                                ## ADDITIONAL CONTEXT
                                %s

                                ## USER REFINEMENT REQUEST
                                "%s"

                                ## INSTRUCTIONS
                                1. **Preserve all existing content** from the original blueprint
                                2. **Add the requested details** based on the refinement prompt
                                3. **Update any sections** affected by code changes (mark with "🔄 Updated")
                                4. **Add new sections** for newly discovered code (mark with "✨ New")
                                5. **Add a "📝 Refinement History" section** at the end documenting:
                                   - What was added
                                   - What was updated
                                   - Code changes detected
                                6. **Use proper Markdown formatting** with headers, code blocks, and examples

                                Output the complete refined blueprint in Markdown format.
                                """,
                                existingBlueprint,
                                changes.toMarkdown(),
                                context.toMarkdown(),
                                refinementPrompt);
        }
}
