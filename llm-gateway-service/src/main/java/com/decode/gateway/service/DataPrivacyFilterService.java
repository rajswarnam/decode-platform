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
 * Filters sensitive data patterns (PII) from user messages before sending to LLM
 * to prevent data privacy violations and comply with internal gateway requirements.
 * 
 * Built-in patterns (always enabled if filterEnabled=true):
 * - Credit Card Numbers (Visa, MasterCard, Amex, Discover)
 * - Social Security Number (SSN) - 9 digits, format XXX-XX-XXXX
 * - Individual Taxpayer Identification Number (ITIN) - 9 digits starting with 9
 * - Employer Identification Number (EIN) - 9 digits, format XX-XXXXXXX
 * - US Bank Routing Number (ABA) - 9 digits
 * - Bank Account Numbers - 8-17 digits (when near banking keywords)
 * - US Phone Numbers - 10 digits, various formats
 * - IP Addresses (IPv4) - 4 octets format
 * 
 * Configurable via application.yaml:
 * - llm.privacy.filter.enabled: true/false
 * - llm.privacy.filter.patterns: List of additional custom regex patterns
 * - llm.privacy.filter.replacement: Replacement string (default: "[REDACTED]")
 * 
 * Note: Pattern order matters. ITIN is checked before SSN since ITINs start with 9.
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
    // Credit Card Numbers (Visa, MasterCard, Amex, Discover)
    private static final Pattern CREDIT_CARD_PATTERN = Pattern.compile(
        "\\b(?:4[0-9]{12}(?:[0-9]{3})?|5[1-5][0-9]{14}|3[47][0-9]{13}|3[0-9]{13}|6(?:011|5[0-9]{2})[0-9]{12})\\b"
    );

    // Social Security Number (SSN) - 9 digits, format XXX-XX-XXXX
    // Excludes invalid prefixes: 000, 666, 900-999 (ITIN range)
    private static final Pattern SSN_PATTERN = Pattern.compile(
        "\\b(?!000)(?!666)(?!9)[0-9]{3}[- ]?(?!00)[0-9]{2}[- ]?(?!0000)[0-9]{4}\\b"
    );

    // Individual Taxpayer Identification Number (ITIN) - 9 digits starting with 9
    // Format: 9XX-XX-XXXX or 9XXXXXXXX
    // Valid middle digit ranges: 50-65, 70-88, 90-92, 94-99 (excludes 89, 93)
    private static final Pattern ITIN_PATTERN = Pattern.compile(
        "\\b9[0-9]{2}[- ]?(?:5[0-9]|6[0-5]|7[0-9]|8[0-8]|9[0-2]|9[4-9])[- ]?[0-9]{4}\\b"
    );

    // Employer Identification Number (EIN) - 9 digits, format XX-XXXXXXX
    // Always starts with 0-9 (first digit), second digit is 0-9
    private static final Pattern EIN_PATTERN = Pattern.compile(
        "\\b[0-9]{2}[- ]?[0-9]{7}\\b"
    );

    // US Bank Routing Number (ABA) - 9 digits, format XXXXXXXX or XXXXX-XXXX
    // Used for wire transfers, direct deposits, ACH transactions
    // First digit must be 0-9, but typically starts with 0, 1, 2, or 3
    private static final Pattern BANK_ROUTING_PATTERN = Pattern.compile(
        "\\b[0-9]{9}\\b|\\b[0-9]{5}[- ]?[0-9]{4}\\b"
    );

    // Bank Account Number - 8-17 digits (varies by bank)
    // More specific: 10-12 digits is most common for US accounts
    // Note: This pattern is conservative to reduce false positives
    // Consider enabling only if you see bank account numbers in your data
    private static final Pattern BANK_ACCOUNT_PATTERN = Pattern.compile(
        "\\b(?:account|acct|checking|savings)[\\s#:]*[0-9]{10,12}\\b"
    );

    // US Phone Number - 10 digits, various formats
    // Format: (XXX) XXX-XXXX, XXX-XXX-XXXX, XXX.XXX.XXXX, or 10 consecutive digits
    // Optional country code +1
    private static final Pattern US_PHONE_PATTERN = Pattern.compile(
        "\\b(?:\\+?1[-.]?)?\\(?([0-9]{3})\\)?[-. ]?([0-9]{3})[-. ]?([0-9]{4})\\b"
    );

    // IP Address (IPv4) - 4 octets, 0-255 each
    // Format: XXX.XXX.XXX.XXX
    private static final Pattern IP_ADDRESS_PATTERN = Pattern.compile(
        "\\b(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\b"
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

        // Apply built-in patterns (order matters - more specific patterns first)
        // ITIN must come before SSN since ITINs start with 9 and could match SSN pattern
        filtered = applyPattern(filtered, ITIN_PATTERN, "ITIN", totalRedactions);
        totalRedactions += countMatches(originalMessage, ITIN_PATTERN);
        
        filtered = applyPattern(filtered, SSN_PATTERN, "SSN", totalRedactions);
        totalRedactions += countMatches(originalMessage, SSN_PATTERN);
        
        filtered = applyPattern(filtered, EIN_PATTERN, "EIN", totalRedactions);
        totalRedactions += countMatches(originalMessage, EIN_PATTERN);
        
        filtered = applyPattern(filtered, BANK_ROUTING_PATTERN, "Bank Routing Number", totalRedactions);
        totalRedactions += countMatches(originalMessage, BANK_ROUTING_PATTERN);
        
        filtered = applyPattern(filtered, BANK_ACCOUNT_PATTERN, "Bank Account Number", totalRedactions);
        totalRedactions += countMatches(originalMessage, BANK_ACCOUNT_PATTERN);
        
        filtered = applyPattern(filtered, CREDIT_CARD_PATTERN, "Credit Card", totalRedactions);
        totalRedactions += countMatches(originalMessage, CREDIT_CARD_PATTERN);
        
        filtered = applyPattern(filtered, US_PHONE_PATTERN, "US Phone Number", totalRedactions);
        totalRedactions += countMatches(originalMessage, US_PHONE_PATTERN);
        
        filtered = applyPattern(filtered, IP_ADDRESS_PATTERN, "IP Address", totalRedactions);
        totalRedactions += countMatches(originalMessage, IP_ADDRESS_PATTERN);

        // Apply custom patterns from configuration
        if (customPatterns != null && !customPatterns.isEmpty()) {
            for (String patternStr : customPatterns) {
                // Skip null, empty, or unresolved placeholder strings
                if (patternStr == null || patternStr.trim().isEmpty()) {
                    continue;
                }
                // Skip patterns that look like unresolved Spring placeholders
                if (patternStr.startsWith("${") || patternStr.contains("${")) {
                    log.debug("Skipping unresolved placeholder pattern: {}", patternStr);
                    continue;
                }
                try {
                    Pattern pattern = Pattern.compile(patternStr);
                    int matches = countMatches(filtered, pattern);
                    if (matches > 0) {
                        filtered = applyPattern(filtered, pattern, "Custom Pattern", totalRedactions);
                        totalRedactions += matches;
                        log.debug("Applied custom pattern, redacted {} matches", matches);
                    }
                } catch (java.util.regex.PatternSyntaxException e) {
                    log.warn("Invalid regex pattern in configuration (skipping): {}", patternStr, e);
                } catch (Exception e) {
                    log.warn("Error processing regex pattern (skipping): {}", patternStr, e);
                }
            }
        }

        // Log if any redactions were made
        if (totalRedactions > 0) {
            log.warn("🔒 Data Privacy Filter: Redacted {} sensitive data pattern(s) from message", totalRedactions);
            log.info("📋 Original Message Length: {} chars, Filtered Message Length: {} chars", 
                    originalMessage.length(), filtered.length());
            if (!originalMessage.equals(filtered)) {
                log.info("📋 Original Message (first 500 chars): {}", 
                        originalMessage.length() > 500 ? originalMessage.substring(0, 500) + "..." : originalMessage);
                log.info("📋 Filtered Message (first 500 chars): {}", 
                        filtered.length() > 500 ? filtered.substring(0, 500) + "..." : filtered);
            }
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
