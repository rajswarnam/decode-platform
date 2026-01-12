package com.decode.code.parser.service;

import org.treesitter.TSParser;
import org.treesitter.TSTree;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterC;
import org.springframework.ai.tool.annotation.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import com.decode.code.parser.dto.ParsedSymbol;

@Service
@Slf4j
public class CTreeSitterService implements LanguageParser {

    private final TSParser parser;

    public CTreeSitterService() {
        this.parser = new TSParser();
        this.parser.setLanguage(new TreeSitterC());
    }

    @Override
    public boolean supports(File file) {
        return file.getName().endsWith(".c") || file.getName().endsWith(".h");
    }

    @Override
    @Tool(description = "Parses a C file and returns the list of struct definitions/symbols found.")
    public List<ParsedSymbol> parseFile(File file) {
        log.info("Parsing C file: {}", file.getAbsolutePath());
        List<ParsedSymbol> symbols = new ArrayList<>();

        try {
            String sourceCode = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            TSTree tree = parser.parseString(null, sourceCode);
            TSNode root = tree.getRootNode();

            traverse(root, sourceCode, symbols);

            return symbols;
        } catch (IOException e) {
            log.error("Failed to read file", e);
            throw new RuntimeException(e);
        }
    }

    private void traverse(TSNode node, String source, List<ParsedSymbol> symbols) {
        if ("field_declaration".equals(node.getType()) || "function_definition".equals(node.getType())) {
            ParsedSymbol symbol = new ParsedSymbol();
            symbol.setCategory(node.getType().replace("_declaration", "").replace("_definition", ""));
            symbol.setStartLine(node.getStartPoint().getRow());
            symbol.setEndLine(node.getEndPoint().getRow());

            // Deep Search for Name and Type within the declaration/definition
            extractMetadata(node, source, symbol);

            if (symbol.getName() != null) {
                symbols.add(symbol);
            }
        }

        // Continue traversal for all nodes
        for (int i = 0; i < node.getChildCount(); i++) {
            traverse(node.getChild(i), source, symbols);
        }
    }

    private void extractMetadata(TSNode node, String source, ParsedSymbol symbol) {
        String type = node.getType();
        if ("field_identifier".equals(type) || "identifier".equals(type)) {
            symbol.setName(source.substring(node.getStartByte(), node.getEndByte()));
        }
        if ("type_identifier".equals(type) || "primitive_type".equals(type)) {
            symbol.setType(source.substring(node.getStartByte(), node.getEndByte()));
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            extractMetadata(node.getChild(i), source, symbol);
        }
    }
}
