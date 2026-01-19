package com.decode.context.orchestrator.controller;

import com.decode.context.orchestrator.service.DictionaryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api/dictionary")
public class DictionaryController {

    private final DictionaryService dictionaryService;

    @Autowired
    public DictionaryController(DictionaryService dictionaryService) {
        this.dictionaryService = dictionaryService;
    }

    @PostMapping("/analyze")
    public ResponseEntity<String> retriggerDictionaryAnalysis() {
        dictionaryService.populateDictionary();
        return ResponseEntity.ok("Dictionary analysis retriggered.");
    }

    /**
     * Streaming endpoint for dictionary population with real-time progress
     * Returns Server-Sent Events (SSE) for real-time updates
     */
    @PostMapping(value = "/populate", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody populateDictionaryStream() {
        org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DictionaryController.class);
        log.info("Dictionary population triggered via streaming endpoint");

        return outputStream -> {
            java.io.PrintWriter writer = new java.io.PrintWriter(
                    new java.io.OutputStreamWriter(outputStream, java.nio.charset.StandardCharsets.UTF_8)
            );

            try {
                // Call streaming-enabled method with progress consumer
                dictionaryService.populateDictionary(progress -> {
                    try {
                        writer.write("event: progress\n");
                        writer.write("data: " + progress + "\n\n");
                        writer.flush();
                    } catch (Exception e) {
                        log.error("Error sending progress event", e);
                    }
                }, true);
            } catch (Exception e) {
                log.error("Error in dictionary population", e);
                try {
                    writer.write("event: error\n");
                    writer.write("data: " + e.getMessage() + "\n\n");
                    writer.flush();
                } catch (Exception flushError) {
                    log.error("Error sending error event", flushError);
                }
            } finally {
                try {
                    writer.write("event: complete\n");
                    writer.write("data: Dictionary population finished\n\n");
                    writer.flush();
                } catch (Exception e) {
                    log.debug("Error closing stream", e);
                }
                writer.close();
            }
        };
    }

    /**
     * Non-streaming endpoint (for backward compatibility or simple triggers)
     * Starts dictionary population in background thread
     */
    @PostMapping("/populate-sync")
    public java.util.Map<String, Object> populateDictionarySync() {
        org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DictionaryController.class);
        log.info("Dictionary population triggered via sync endpoint");
        java.util.Map<String, Object> response = new java.util.HashMap<>();
        try {
            // Run in background thread
            Thread thread = new Thread(() -> {
                try {
                    dictionaryService.populateDictionary(); // Uses original method (no progress)
                } catch (Exception e) {
                    log.error("Dictionary population failed", e);
                }
            });
            thread.start();
            response.put("status", "STARTED");
            response.put("message", "Dictionary population started in background. Use /populate for streaming updates.");
            return response;
        } catch (Exception e) {
            log.error("Failed to start dictionary population", e);
            response.put("status", "ERROR");
            response.put("message", e.getMessage());
            return response;
        }
    }
}
