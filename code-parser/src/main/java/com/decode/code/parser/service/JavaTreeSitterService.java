package com.decode.code.parser.service;

import org.treesitter.TSParser;
import org.treesitter.TSTree;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterJava;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import com.decode.code.parser.dto.ParsedSymbol;

import org.springframework.ai.tool.annotation.Tool;

@Service
@Slf4j
public class JavaTreeSitterService implements LanguageParser {

    private final TSParser parser;

    public JavaTreeSitterService() {
        this.parser = new TSParser();
        this.parser.setLanguage(new TreeSitterJava());
    }

    @Override
    public boolean supports(File file) {
        return file.getName().endsWith(".java");
    }

    @Override
    @Tool(description = "Parses a Java file and returns the list of class definitions found.")
    public List<ParsedSymbol> parseFile(File file) {
        log.info("Parsing file: {}", file.getAbsolutePath());
        List<ParsedSymbol> symbols = new ArrayList<>();

        try {
            String sourceCode = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            TSTree tree = parser.parseString(null, sourceCode);
            TSNode root = tree.getRootNode();

            traverse(root, symbols, sourceCode);

            return symbols;
        } catch (IOException e) {
            log.error("Failed to read file", e);
            throw new RuntimeException(e);
        }
    }

    private void traverse(TSNode node, List<ParsedSymbol> symbols, String sourceCode) {
        if ("class_declaration".equals(node.getType())) {
            ParsedSymbol symbol = new ParsedSymbol();
            symbol.setCategory("class");
            symbol.setStartLine(node.getStartPoint().getRow());
            symbol.setEndLine(node.getEndPoint().getRow());

            // Find name
            TSNode nameNode = node.getChildByFieldName("name");
            if (nameNode != null) {
                symbol.setName(extractText(nameNode, sourceCode));
            } else {
                symbol.setName("Anonymous");
            }
            symbols.add(symbol);
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            traverse(node.getChild(i), symbols, sourceCode);
        }
    }

    private String extractText(TSNode node, String source) {
        // Simplified extraction assuming no multi-byte characters messing up offsets
        // Ideally we use byte-level extraction, but String-level is okay for PoC on
        // ASCII
        int start = node.getStartByte();
        int end = node.getEndByte();
        if (start >= 0 && end <= source.length() && start < end) {
            return source.substring(start, end);
        }
        // Fallback using lines if byte offset fails due to encoding
        return "Unknown";
    }
}
