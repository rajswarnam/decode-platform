package com.decode.code.parser;

import com.decode.code.parser.domain.Project;
import com.decode.code.parser.domain.SourceFile;
import com.decode.code.parser.domain.Symbol;
import com.decode.code.parser.dto.ParsedSymbol;
import com.decode.code.parser.repository.ProjectRepository;
import com.decode.code.parser.repository.SourceFileRepository;
import com.decode.code.parser.repository.SymbolRepository;
import com.decode.code.parser.service.LanguageParser;
import com.decode.code.parser.service.ParserOrchestratorService;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.messages.Item;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;

@Component
@RequiredArgsConstructor
@Slf4j
public class ParserRunner implements CommandLineRunner {

    private final ProjectRepository projectRepository;
    private final ParserOrchestratorService parserService;

    @Override
    @Transactional
    public void run(String... args) throws Exception {
        log.info("--- Starting Code Parser Batch Job (Cloud-Native Mode) ---");

        List<Project> projects = projectRepository.findAll();
        log.info("Found {} projects to process", projects.size());

        if (projects.isEmpty()) {
            log.warn("No projects found in DB. Please run Ingestion Engine first.");
            return;
        }

        for (Project project : projects) {
            try {
                parserService.processProject(project);
            } catch (Exception e) {
                log.error("Parsing failed for project {}", project.getName(), e);
            }
        }

        log.info("--- Code Parser Batch Job Complete ---");
    }
}
