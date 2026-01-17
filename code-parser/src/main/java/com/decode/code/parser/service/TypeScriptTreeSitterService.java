package com.decode.code.parser.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.decode.code.parser.dto.ParsedSymbol;

@Service
@Slf4j
public class TypeScriptTreeSitterService implements LanguageParser {

    // Regex patterns for TypeScript/JavaScript symbol extraction
    private static final Pattern CLASS_PATTERN = Pattern.compile(
        "^(?:export\\s+)?(?:abstract\\s+)?class\\s+(\\w+)(?:\\s+extends\\s+\\w+)?(?:\\s+implements\\s+[^{]+)?\\s*\\{",
        Pattern.MULTILINE | Pattern.CASE_INSENSITIVE
    );
    
    private static final Pattern INTERFACE_PATTERN = Pattern.compile(
        "^(?:export\\s+)?interface\\s+(\\w+)(?:\\s+extends\\s+[^{]+)?\\s*\\{",
        Pattern.MULTILINE | Pattern.CASE_INSENSITIVE
    );
    
    private static final Pattern ENUM_PATTERN = Pattern.compile(
        "^(?:export\\s+)?enum\\s+(\\w+)\\s*\\{",
        Pattern.MULTILINE | Pattern.CASE_INSENSITIVE
    );
    
    private static final Pattern FUNCTION_PATTERN = Pattern.compile(
        "^(?:export\\s+)?(?:async\\s+)?(?:function\\s+)?(?:const\\s+)?(\\w+)\\s*[=:]\\s*(?:async\\s+)?(?:function|\\()",
        Pattern.MULTILINE | Pattern.CASE_INSENSITIVE
    );
    
    private static final Pattern METHOD_PATTERN = Pattern.compile(
        "\\s+(?:async\\s+)?(\\w+)\\s*\\([^)]*\\)(?:\\s*:\\s*[^{]+)?\\s*\\{",
        Pattern.MULTILINE | Pattern.CASE_INSENSITIVE
    );
    
    private static final Pattern DECORATOR_PATTERN = Pattern.compile(
        "@(Component|Injectable|Directive|Pipe|NgModule)\\s*\\([^)]*\\)",
        Pattern.MULTILINE | Pattern.CASE_INSENSITIVE
    );
    
    private static final Pattern VARIABLE_PATTERN = Pattern.compile(
        "^(?:export\\s+)?(?:const|let|var)\\s+(\\w+)\\s*[:=]",
        Pattern.MULTILINE | Pattern.CASE_INSENSITIVE
    );
    
    private static final Pattern PROPERTY_PATTERN = Pattern.compile(
        "\\s+(?:public|private|protected|readonly)?\\s*(?:static)?\\s*(\\w+)\\s*[:;]",
        Pattern.MULTILINE | Pattern.CASE_INSENSITIVE
    );

    @Override
    public boolean supports(File file) {
        String fileName = file.getName().toLowerCase();
        return fileName.endsWith(".ts") || 
               fileName.endsWith(".tsx") || 
               fileName.endsWith(".js") || 
               fileName.endsWith(".jsx");
    }

    @Override
    public List<ParsedSymbol> parseFile(File file) {
        log.info("Parsing TypeScript/JavaScript file: {}", file.getAbsolutePath());
        List<ParsedSymbol> symbols = new ArrayList<>();

        try {
            String sourceCode;
            if (file.exists()) {
                sourceCode = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            } else {
                log.warn("Local file not found: {}", file.getName());
                return symbols;
            }

            // Extract classes
            extractMatches(CLASS_PATTERN, sourceCode, symbols, "class", file);
            
            // Extract interfaces
            extractMatches(INTERFACE_PATTERN, sourceCode, symbols, "interface", file);
            
            // Extract enums
            extractMatches(ENUM_PATTERN, sourceCode, symbols, "enum", file);
            
            // Extract functions
            extractMatches(FUNCTION_PATTERN, sourceCode, symbols, "function", file);
            
            // Extract methods (within classes)
            extractMatches(METHOD_PATTERN, sourceCode, symbols, "method", file);
            
            // Extract variables
            extractMatches(VARIABLE_PATTERN, sourceCode, symbols, "variable", file);
            
            // Extract properties
            extractMatches(PROPERTY_PATTERN, sourceCode, symbols, "property", file);
            
            // Extract Angular decorators (components, services, directives, pipes)
            extractAngularDecorators(sourceCode, symbols, file);

            return symbols;
        } catch (IOException e) {
            log.error("Failed to read file", e);
            throw new RuntimeException(e);
        }
    }

    private void extractMatches(Pattern pattern, String sourceCode, List<ParsedSymbol> symbols, 
                                String category, File file) {
        Matcher matcher = pattern.matcher(sourceCode);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (name != null && !name.isEmpty()) {
                ParsedSymbol symbol = new ParsedSymbol();
                symbol.setCategory(category);
                symbol.setName(name);
                
                // Calculate line number
                int lineNumber = sourceCode.substring(0, matcher.start()).split("\n").length;
                symbol.setStartLine(lineNumber);
                symbol.setEndLine(lineNumber); // Approximate
                
                symbols.add(symbol);
            }
        }
    }

    private void extractAngularDecorators(String sourceCode, List<ParsedSymbol> symbols, File file) {
        Matcher decoratorMatcher = DECORATOR_PATTERN.matcher(sourceCode);
        while (decoratorMatcher.find()) {
            String decoratorType = decoratorMatcher.group(1).toLowerCase();
            
            // Find the class declaration after this decorator
            int decoratorEnd = decoratorMatcher.end();
            String remainingCode = sourceCode.substring(decoratorEnd);
            
            Matcher classMatcher = Pattern.compile("^\\s*class\\s+(\\w+)", Pattern.MULTILINE).matcher(remainingCode);
            if (classMatcher.find()) {
                String className = classMatcher.group(1);
                
                ParsedSymbol symbol = new ParsedSymbol();
                symbol.setCategory(decoratorType); // component, service, directive, pipe
                symbol.setName(className);
                
                // Calculate line number
                int decoratorLine = sourceCode.substring(0, decoratorMatcher.start()).split("\n").length;
                symbol.setStartLine(decoratorLine);
                symbol.setEndLine(decoratorLine);
                
                symbols.add(symbol);
            }
        }
    }
}
