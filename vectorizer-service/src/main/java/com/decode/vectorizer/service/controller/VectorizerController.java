package com.decode.vectorizer.service.controller;

import com.decode.vectorizer.service.service.VectorizerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/vectorizer")
@RequiredArgsConstructor
@Slf4j
public class VectorizerController {

    private final VectorizerService vectorizerService;

    @PostMapping("/trigger")
    public ResponseEntity<String> triggerVectorization(@RequestParam UUID projectId) {
        log.info("Received trigger for vectorizing project ID: {}", projectId);
        new Thread(() -> {
            try {
                vectorizerService.vectorizeProject(projectId);
            } catch (Exception e) {
                log.error("Vectorization failed", e);
            }
        }).start();
        return ResponseEntity.ok("Vectorization triggered for " + projectId);
    }
}
