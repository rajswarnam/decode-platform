package com.decode.context.orchestrator.runner;

import com.decode.context.orchestrator.service.DictionaryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class DictionaryRunner implements CommandLineRunner {

    private final DictionaryService dictionaryService;

    @Override
    public void run(String... args) throws Exception {
        try {
            dictionaryService.populateDictionary();
        } catch (Exception e) {
            log.error("Dictionary Population Failed", e);
        }
    }
}
