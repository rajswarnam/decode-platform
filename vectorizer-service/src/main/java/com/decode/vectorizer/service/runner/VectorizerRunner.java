package com.decode.vectorizer.service.runner;

import com.decode.vectorizer.service.service.VectorizerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class VectorizerRunner implements CommandLineRunner {

    private final VectorizerService vectorizerService;

    @Override
    public void run(String... args) throws Exception {
        try {
            vectorizerService.vectorizerAllSymbols();
        } catch (Exception e) {
            log.error("Vectorization failed", e);
        }
    }
}
