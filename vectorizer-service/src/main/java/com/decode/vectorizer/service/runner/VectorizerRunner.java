package com.decode.vectorizer.service.runner;

import com.decode.vectorizer.service.service.VectorizerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class VectorizerRunner implements CommandLineRunner {

    private final VectorizerService vectorizerService;

    @Value("${vectorizer.auto-vectorize-on-startup:false}")
    private boolean autoVectorizeOnStartup;

    @Override
    public void run(String... args) throws Exception {
        if (!autoVectorizeOnStartup) {
            log.info("Vectorization on startup is disabled by configuration.");
            log.info("Symbols will be vectorized when triggered by code-parser or via API.");
            return;
        }

        log.info("Auto-vectorization on startup is enabled. Starting batch vectorization...");
        try {
            vectorizerService.vectorizerAllSymbols();
        } catch (Exception e) {
            log.error("Vectorization failed", e);
        }
    }
}
