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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.decode.code.parser.dto.ParsedSymbol;
import com.decode.code.parser.dto.ParsedRelationship;

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
    
    @Override
    public List<ParsedRelationship> extractRelationships(File file, List<ParsedSymbol> symbols) {
        log.info("Extracting C relationships from: {}", file.getAbsolutePath());
        List<ParsedRelationship> relationships = new ArrayList<>();
        
        try {
            String sourceCode = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            TSTree tree = parser.parseString(null, sourceCode);
            TSNode root = tree.getRootNode();
            
            // Build a map of function names for quick lookup
            Map<String, ParsedSymbol> functionSymbols = new HashMap<>();
            for (ParsedSymbol symbol : symbols) {
                if ("function".equals(symbol.getCategory()) && symbol.getName() != null) {
                    functionSymbols.put(symbol.getName(), symbol);
                }
            }
            
            // Track current function context
            List<String> currentFunctionStack = new ArrayList<>();
            currentFunctionStack.add(null); // Root level
            
            // Traverse AST and extract relationships
            extractRelationshipsTraverse(root, sourceCode, relationships, functionSymbols, currentFunctionStack);
            
            log.info("Extracted {} C relationships from {}", relationships.size(), file.getName());
            return relationships;
        } catch (IOException e) {
            log.error("Failed to extract relationships from C file", e);
            return relationships;
        }
    }
    
    private void extractRelationshipsTraverse(TSNode node, String source, 
                                              List<ParsedRelationship> relationships,
                                              Map<String, ParsedSymbol> functionSymbols,
                                              List<String> currentFunctionStack) {
        String nodeType = node.getType();
        
        // Track current function context
        if ("function_definition".equals(nodeType)) {
            String functionName = extractFunctionName(node, source);
            if (functionName != null) {
                currentFunctionStack.add(functionName);
            }
        }
        
        // Extract function calls (call_expression)
        if ("call_expression".equals(nodeType)) {
            String caller = currentFunctionStack.get(currentFunctionStack.size() - 1);
            String callee = extractCalleeName(node, source);
            
            if (callee != null && caller != null) {
                // Only create relationship if callee is a known function symbol
                if (functionSymbols.containsKey(callee)) {
                    ParsedRelationship rel = new ParsedRelationship(
                            caller,
                            callee,
                            "CALLS",
                            node.getStartPoint().getRow() + 1,
                            node.getStartPoint().getColumn(),
                            extractCallContext(node, source)
                    );
                    relationships.add(rel);
                }
            }
        }
        
        // Extract pointer dereferences (field_expression with ->)
        if ("field_expression".equals(nodeType)) {
            String currentFunction = currentFunctionStack.get(currentFunctionStack.size() - 1);
            String pointerDeref = extractPointerDereference(node, source);
            
            if (pointerDeref != null && currentFunction != null) {
                ParsedRelationship rel = new ParsedRelationship(
                        currentFunction,
                        pointerDeref,
                        "POINTER_TO",
                        node.getStartPoint().getRow() + 1,
                        node.getStartPoint().getColumn(),
                        "Pointer dereference: ->"
                );
                relationships.add(rel);
            }
        }
        
        // Continue traversal
        for (int i = 0; i < node.getChildCount(); i++) {
            extractRelationshipsTraverse(node.getChild(i), source, relationships, 
                                        functionSymbols, currentFunctionStack);
        }
        
        // Pop function context when exiting function_definition
        if ("function_definition".equals(nodeType)) {
            if (currentFunctionStack.size() > 1) {
                currentFunctionStack.remove(currentFunctionStack.size() - 1);
            }
        }
    }
    
    private String extractFunctionName(TSNode node, String source) {
        // Find declarator node which contains the function name
        for (int i = 0; i < node.getChildCount(); i++) {
            TSNode child = node.getChild(i);
            if ("function_declarator".equals(child.getType()) || "identifier".equals(child.getType())) {
                String name = extractIdentifierFromNode(child, source);
                if (name != null) return name;
            }
            // Recursively search in children
            String name = extractFunctionName(child, source);
            if (name != null) return name;
        }
        return null;
    }
    
    private String extractCalleeName(TSNode callNode, String source) {
        // The first child of call_expression is usually the function identifier
        for (int i = 0; i < callNode.getChildCount(); i++) {
            TSNode child = callNode.getChild(i);
            if ("identifier".equals(child.getType())) {
                return source.substring(child.getStartByte(), child.getEndByte());
            }
            // Handle cases like ptr->func() or obj.func()
            if ("field_expression".equals(child.getType())) {
                // Extract the function name from field_expression
                return extractFieldName(child, source);
            }
            // Recursively search
            String name = extractCalleeName(child, source);
            if (name != null) return name;
        }
        return null;
    }
    
    private String extractFieldName(TSNode node, String source) {
        // Find the last identifier in a field_expression (the actual field/method name)
        for (int i = node.getChildCount() - 1; i >= 0; i--) {
            TSNode child = node.getChild(i);
            if ("field_identifier".equals(child.getType()) || "identifier".equals(child.getType())) {
                return source.substring(child.getStartByte(), child.getEndByte());
            }
        }
        return null;
    }
    
    private String extractPointerDereference(TSNode node, String source) {
        // Check if this field_expression uses -> operator
        String nodeText = source.substring(node.getStartByte(), node.getEndByte());
        if (nodeText.contains("->")) {
            // Extract the field name (right side of ->)
            return extractFieldName(node, source);
        }
        return null;
    }
    
    private String extractIdentifierFromNode(TSNode node, String source) {
        if ("identifier".equals(node.getType())) {
            return source.substring(node.getStartByte(), node.getEndByte());
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            String name = extractIdentifierFromNode(node.getChild(i), source);
            if (name != null) return name;
        }
        return null;
    }
    
    private String extractCallContext(TSNode callNode, String source) {
        // Extract a snippet of the call for context (limit to reasonable length)
        String snippet = source.substring(callNode.getStartByte(), 
                                          Math.min(callNode.getEndByte(), callNode.getStartByte() + 50));
        return snippet.trim();
    }
}
