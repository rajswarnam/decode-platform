package com.decode.gateway.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * Data Privacy Filter Service
 * 
 * Filters sensitive data patterns (credit cards, SSN, etc.) from user messages
 * before sending to LLM to prevent data privacy violations.
 * 
 * Configurable via application.yaml:
 * - llm.privacy.filter.enabled: true/false
 * - llm.privacy.filter.patterns: List of regex patterns
 * - llm.privacy.filter.replacement: Replacement string (default: "[REDACTED]")
 */
@Service
@Slf4j
public class DataPrivacyFilterService {

    @Value("${llm.privacy.filter.enabled:true}")
    private boolean filterEnabled;

    @Value("${llm.privacy.filter.replacement:[REDACTED]}")
    private String replacementText;

    @Value("${llm.privacy.filter.patterns:}")
    private List<String> customPatterns;

    // Built-in patterns (always enabled if filterEnabled=true)
    private static final Pattern CREDIT_CARD_PATTERN = Pattern.compile(
        "\\b(?:4[0-9]{12}(?:[0-9]{3})?|5[1-5][0-9]{14}|3[47][0-9]{13}|3[0-9]{13}|6(?:011|5[0-9]{2})[0-9]{12})\\b"
    );

    private static final Pattern SSN_PATTERN = Pattern.compile(
        "\\b(?!000)(?!666)(?!9)[0-9]{3}[- ]?(?!00)[0-9]{2}[- ]?(?!0000)[0-9]{4}\\b"
    );

    // Optional: Email pattern (can be disabled if needed)
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
        "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}\\b"
    );

    /**
     * Filter sensitive data from message content
     * 
     * @param originalMessage Original message content
     * @return Filtered message with sensitive data replaced
     */
    public String filterSensitiveData(String originalMessage) {
        if (!filterEnabled || originalMessage == null || originalMessage.isEmpty()) {
            return originalMessage;
        }

        String filtered = originalMessage;
        int totalRedactions = 0;

        // Apply built-in patterns
        filtered = applyPattern(filtered, CREDIT_CARD_PATTERN, "Credit Card", totalRedactions);
        totalRedactions += countMatches(originalMessage, CREDIT_CARD_PATTERN);
        
        filtered = applyPattern(filtered, SSN_PATTERN, "SSN", totalRedactions);
        totalRedactions += countMatches(originalMessage, SSN_PATTERN);

        // Apply custom patterns from configuration
        if (customPatterns != null && !customPatterns.isEmpty()) {
            for (String patternStr : customPatterns) {
                try {
                    Pattern pattern = Pattern.compile(patternStr);
                    int matches = countMatches(filtered, pattern);
                    if (matches > 0) {
                        filtered = applyPattern(filtered, pattern, "Custom Pattern", totalRedactions);
                        totalRedactions += matches;
                        log.debug("Applied custom pattern, redacted {} matches", matches);
                    }
                } catch (Exception e) {
                    log.warn("Invalid regex pattern in configuration: {}", patternStr, e);
                }
            }
        }

        // Log if any redactions were made
        if (totalRedactions > 0) {
            log.warn("🔒 Data Privacy Filter: Redacted {} sensitive data pattern(s) from message", totalRedactions);
        }

        return filtered;
    }

    /**
     * Apply a pattern and replace matches with replacement text
     */
    private String applyPattern(String text, Pattern pattern, String patternName, int existingRedactions) {
        Matcher matcher = pattern.matcher(text);
        StringBuffer result = new StringBuffer();
        int matchCount = 0;
        
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacementText));
            matchCount++;
        }
        matcher.appendTail(result);
        
        if (matchCount > 0) {
            log.debug("Redacted {} {} pattern(s)", matchCount, patternName);
        }
        
        return result.toString();
    }

    /**
     * Count matches without replacing (for logging)
     */
    private int countMatches(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /**
     * Check if filtering is enabled
     */
    public boolean isFilterEnabled() {
        return filterEnabled;
    }
}
