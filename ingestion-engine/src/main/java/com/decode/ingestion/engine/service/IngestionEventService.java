package com.decode.ingestion.engine.service;

import com.decode.ingestion.engine.domain.Project;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
@Slf4j
public class IngestionEventService {

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE); // Infinite timeout
        
        emitter.onCompletion(() -> {
            emitters.remove(emitter);
            log.debug("Emitter completed");
        });
        emitter.onTimeout(() -> {
            emitters.remove(emitter);
            log.debug("Emitter timed out");
        });
        emitter.onError((e) -> {
            emitters.remove(emitter);
            log.debug("Emitter error: {}", e.getMessage());
        });

        emitters.add(emitter);
        
        // specific connection keep-alive
        try {
            emitter.send(SseEmitter.event().name("init").data("Connected"));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
        
        return emitter;
    }

    public void sendProgress(Project project) {
        // Run in separate thread to avoid blocking the ingestion loop
        executor.submit(() -> {
             List<SseEmitter> deadEmitters = new java.util.ArrayList<>();
             
             // Convert Project to a safe serializable format
             java.util.Map<String, Object> projectData = new java.util.HashMap<>();
             projectData.put("id", project.getId() != null ? project.getId().toString() : null);
             projectData.put("name", project.getName());
             projectData.put("domain", project.getDomain());
             projectData.put("description", project.getDescription());
             projectData.put("basePath", project.getBasePath());
             projectData.put("gitUrl", project.getGitUrl());
             projectData.put("techStack", project.getTechStack());
             projectData.put("status", project.getStatus());
             projectData.put("ingestionProgress", project.getIngestionProgress());
             projectData.put("currentFile", project.getCurrentFile());
             projectData.put("estimatedRemainingSeconds", project.getEstimatedRemainingSeconds());
             projectData.put("totalFiles", project.getTotalFiles());
             projectData.put("processedFiles", project.getProcessedFiles());
             if (project.getCreatedAt() != null) {
                 projectData.put("createdAt", project.getCreatedAt().toString());
             }
             
             for (SseEmitter emitter : emitters) {
                 try {
                     emitter.send(SseEmitter.event()
                         .name("progress")
                         .data(projectData));
                 } catch (Exception e) {
                     log.warn("Failed to send progress to emitter: {}", e.getMessage());
                     deadEmitters.add(emitter);
                 }
             }
             
             emitters.removeAll(deadEmitters);
        });
    }
    
    /**
     * Send a text event message to all connected clients
     */
    public void sendEvent(String message) {
        executor.submit(() -> {
            List<SseEmitter> deadEmitters = new java.util.ArrayList<>();
            
            log.info("📡 SSE Event: {}", message);
            
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event()
                        .name("message")
                        .data(message));
                } catch (Exception e) {
                    log.debug("Failed to send to emitter: {}", e.getMessage());
                    deadEmitters.add(emitter);
                }
            }
            
            emitters.removeAll(deadEmitters);
        });
    }
}
