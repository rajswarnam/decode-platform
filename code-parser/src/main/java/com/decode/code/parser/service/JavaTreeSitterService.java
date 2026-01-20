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
            String sourceCode;
            if (file.exists()) {
                sourceCode = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            } else {
                // FALLBACK: Try to fetch from MinIO if local file is missing (Generic Cloud
                // Support)
                log.warn("Local file not found, attempting MinIO fetch for: {}", file.getName());
                // Note: In a real implementation, we would need the semantic storageKey.
                // For now, we assume the parsing happens during ingestion when file exists.
                // If we want "Lazy Parsing" after cleanup, we need to pass the storageKey or
                // project context.
                return symbols;
            }

            TSTree tree = parser.parseString(null, sourceCode);
            TSNode root = tree.getRootNode();

            traverse(root, symbols, sourceCode);

            return symbols;
        } catch (IOException e) {
            log.error("Failed to read file", e);
            throw new RuntimeException(e);
        }
    }

    public List<ParsedSymbol> parseContent(String sourceCode) {
        List<ParsedSymbol> symbols = new ArrayList<>();
        try {
            TSTree tree = parser.parseString(null, sourceCode);
            TSNode root = tree.getRootNode();
            
            if (root == null || root.isNull()) {
                log.warn("Parsed tree has null root node");
                return symbols;
            }
            
            traverse(root, symbols, sourceCode);
        } catch (org.treesitter.TSException e) {
            log.warn("Tree-sitter parsing error: {}", e.getMessage());
            // Return empty list on parsing errors
        } catch (Exception e) {
            log.error("Unexpected error parsing content: {}", e.getMessage(), e);
            // Return empty list on unexpected errors
        }
        return symbols;
    }

    private void traverse(TSNode node, List<ParsedSymbol> symbols, String sourceCode) {
        // Null check - handle null nodes gracefully
        if (node == null || node.isNull()) {
            return;
        }
        
        String nodeType = node.getType();
        
        // Extract classes
        if ("class_declaration".equals(nodeType)) {
            ParsedSymbol symbol = createSymbol(node, sourceCode, "class");
            if (symbol != null) symbols.add(symbol);
        }
        // Extract interfaces
        else if ("interface_declaration".equals(nodeType)) {
            ParsedSymbol symbol = createSymbol(node, sourceCode, "interface");
            if (symbol != null) symbols.add(symbol);
        }
        // Extract enums
        else if ("enum_declaration".equals(nodeType)) {
            ParsedSymbol symbol = createSymbol(node, sourceCode, "enum");
            if (symbol != null) symbols.add(symbol);
        }
        // Extract methods
        else if ("method_declaration".equals(nodeType) || "constructor_declaration".equals(nodeType)) {
            ParsedSymbol symbol = createSymbol(node, sourceCode, 
                "constructor_declaration".equals(nodeType) ? "constructor" : "method");
            if (symbol != null) symbols.add(symbol);
        }
        // Extract fields
        else if ("field_declaration".equals(nodeType)) {
            // A field_declaration can contain multiple variable declarators
            // Extract each variable as a separate symbol
            for (int i = 0; i < node.getChildCount(); i++) {
                TSNode child = node.getChild(i);
                if (child == null || child.isNull()) {
                    continue; // Skip null children
                }
                
                if ("variable_declarator".equals(child.getType())) {
                    TSNode nameNode = child.getChildByFieldName("name");
                    if (nameNode != null && !nameNode.isNull()) {
                        try {
                            ParsedSymbol symbol = new ParsedSymbol();
                            symbol.setCategory("field");
                            symbol.setStartLine(node.getStartPoint().getRow());
                            symbol.setEndLine(node.getEndPoint().getRow());
                            symbol.setName(extractText(nameNode, sourceCode));
                            
                            // Skip if name extraction failed
                            if (symbol.getName() == null || symbol.getName().isEmpty() || "Unknown".equals(symbol.getName())) {
                                continue;
                            }
                            
                            // Try to extract type from parent field_declaration
                            TSNode typeNode = node.getChildByFieldName("type");
                            if (typeNode == null || typeNode.isNull()) {
                                // Try finding type_identifier in the first few children
                                for (int j = 0; j < node.getChildCount() && j < 5; j++) {
                                    TSNode candidate = node.getChild(j);
                                    if (candidate != null && !candidate.isNull() && 
                                        ("type_identifier".equals(candidate.getType()) || 
                                         "generic_type".equals(candidate.getType()) ||
                                         "array_type".equals(candidate.getType()))) {
                                        typeNode = candidate;
                                        break;
                                    }
                                }
                            }
                            if (typeNode != null && !typeNode.isNull()) {
                                String typeText = extractText(typeNode, sourceCode);
                                if (!"Unknown".equals(typeText)) {
                                    symbol.setType(typeText);
                                }
                            }
                            symbols.add(symbol);
                        } catch (Exception e) {
                            log.warn("Failed to extract field symbol: {}", e.getMessage());
                            // Continue with next field
                        }
                    }
                }
            }
        }

        // Continue traversal for all children
        for (int i = 0; i < node.getChildCount(); i++) {
            TSNode child = node.getChild(i);
            if (child != null && !child.isNull()) {
                traverse(child, symbols, sourceCode);
            }
        }
    }

    private ParsedSymbol createSymbol(TSNode node, String sourceCode, String category) {
        // Null check - handle null nodes gracefully
        if (node == null || node.isNull()) {
            log.debug("createSymbol called with null node for category: {}", category);
            return null;
        }
        
        try {
            ParsedSymbol symbol = new ParsedSymbol();
            symbol.setCategory(category);
            symbol.setStartLine(node.getStartPoint().getRow());
            symbol.setEndLine(node.getEndPoint().getRow());

            // Find name
            TSNode nameNode = node.getChildByFieldName("name");
            if (nameNode != null && !nameNode.isNull()) {
                symbol.setName(extractText(nameNode, sourceCode));
            } else {
                // Fallback: look for identifier in children
                for (int i = 0; i < node.getChildCount() && i < 10; i++) {
                    TSNode child = node.getChild(i);
                    if (child != null && !child.isNull() && 
                        ("identifier".equals(child.getType()) || 
                         "type_identifier".equals(child.getType()))) {
                        symbol.setName(extractText(child, sourceCode));
                        break;
                    }
                }
                if (symbol.getName() == null || symbol.getName().isEmpty() || "Unknown".equals(symbol.getName())) {
                    symbol.setName("Anonymous");
                }
            }
            
            // If we still don't have a valid name, return null (can't create symbol without name)
            if (symbol.getName() == null || symbol.getName().isEmpty() || "Unknown".equals(symbol.getName())) {
                log.debug("createSymbol: Could not extract name for {} node", category);
                return null;
            }

            // Try to extract type for methods (return type)
            if ("method".equals(category) || "constructor".equals(category)) {
                TSNode typeNode = node.getChildByFieldName("type");
                if (typeNode == null || typeNode.isNull()) {
                    // For constructors, type is the class name
                    if ("constructor".equals(category)) {
                        // Constructor name is typically the first identifier
                        if (nameNode != null && !nameNode.isNull()) {
                            symbol.setType(extractText(nameNode, sourceCode));
                        }
                    }
                } else {
                    String typeText = extractText(typeNode, sourceCode);
                    if (!"Unknown".equals(typeText)) {
                        symbol.setType(typeText);
                    }
                }
            }

            return symbol;
        } catch (Exception e) {
            log.warn("createSymbol failed for category {}: {}", category, e.getMessage());
            return null;
        }
    }

    private String extractText(TSNode node, String source) {
        // Null check - handle null nodes gracefully
        if (node == null || node.isNull()) {
            log.debug("extractText called with null node");
            return "Unknown";
        }
        
        try {
            // Simplified extraction assuming no multi-byte characters messing up offsets
            // Ideally we use byte-level extraction, but String-level is okay for PoC on
            // ASCII
            int start = node.getStartByte();
            int end = node.getEndByte();
            if (start >= 0 && end <= source.length() && start < end) {
                return source.substring(start, end);
            }
            // Fallback using lines if byte offset fails due to encoding
            log.debug("extractText: invalid byte offsets (start: {}, end: {}, source length: {})", start, end, source.length());
            return "Unknown";
        } catch (Exception e) {
            log.warn("extractText failed for node type {}: {}", node.getType(), e.getMessage());
            return "Unknown";
        }
    }
}
