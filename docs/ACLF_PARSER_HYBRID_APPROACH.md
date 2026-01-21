# ACLF Parser Hybrid Approach

## Overview

The ACLF parser uses a **hybrid approach** combining regex patterns and LLM-based extraction to handle the diverse patterns found in 50K+ ACLF files.

## Architecture

### 1. Regex Patterns (Fast, Deterministic)

**Purpose:** Extract known/common patterns quickly and deterministically.

**Patterns Extracted:**
- ExternalDatalist definitions
- Datafield definitions  
- Transaction definitions
- FormBlock, FormReport, Calculation definitions
- Field references in `Data = FQDF.XXX.YYY[Z]` assignments
- FQDF field references in code (`FQDF.LOCAL.NUM10`, `FQDF.SYSTEM.CONDCODE`)
- Datafield names in Datalist Entries (`{ Name = Datafield, [_BCMOPID]; }`)
- Field references in function parameters (`MoveToField(Source: FQDF.XXX.YYY)`)

**Advantages:**
- Fast execution
- Deterministic results
- No API costs
- Handles most common patterns

**Limitations:**
- Only catches patterns we've explicitly coded
- May miss new/unknown patterns
- Struggles with deeply nested or complex structures

### 2. LLM-Based Extraction (Adaptive, Comprehensive)

**Purpose:** Catch unknown patterns and complex structures that regex might miss.

**What It Extracts:**
- Field references in any format (not just known patterns)
- Datafield names from any structure
- Variable names, field names, identifiers in assignments/expressions
- Complex nested structures
- Patterns we haven't seen before

**How It Works:**
1. After regex patterns run, LLM analyzes the file content
2. LLM identifies all field-like identifiers and data elements
3. Returns JSON array of extracted fields
4. Deduplicates against regex-extracted fields

**Advantages:**
- Adapts to new patterns without code changes
- Understands context better than regex
- Catches nested/complex structures
- Handles variations in syntax

**Limitations:**
- Slower (API call per file)
- Costs tokens/API calls
- May have false positives
- Requires LLM gateway to be available

## Configuration

### Enable/Disable LLM Extraction

In `code-parser/src/main/resources/application.yaml`:

```yaml
parser:
  aclf:
    llm-extraction:
      # Enable LLM-based extraction (default: true)
      enabled: true
      
      # Maximum file size to process with LLM (default: 50000 chars)
      # Larger files are skipped to avoid token limits
      max-file-size: 50000
```

### Performance Considerations

**For 50K+ files:**
- **Regex-only mode** (`enabled: false`): Fast, but may miss some fields
- **Hybrid mode** (`enabled: true`): More comprehensive, but slower

**Recommendation:**
- Start with hybrid mode to discover all patterns
- Once patterns are known, add them as regex patterns
- Consider disabling LLM for production if performance is critical

## File Size Handling

The LLM extraction automatically handles large files:

- **Files ≤ 20K chars:** Full content sent to LLM
- **Files 20K-50K chars:** First 10K + last 10K chars (sample)
- **Files > 50K chars:** Skipped (use regex only)

This prevents token limit issues while still extracting fields from most files.

## Extraction Flow

```
ACLF File
    ↓
[1] Regex Patterns (Patterns 1-10)
    ↓
    Extract known patterns
    ↓
[2] LLM Extraction (if enabled)
    ↓
    Analyze content for unknown patterns
    ↓
[3] Deduplication
    ↓
    Combine results, remove duplicates
    ↓
Final Symbol List
```

## Adding New Regex Patterns

When you discover a new pattern:

1. **Add regex pattern** to `parseDslFormat()` method
2. **Test** on sample files
3. **Document** the pattern in code comments
4. **Consider** if LLM would catch it (if yes, regex is optional optimization)

Example:
```java
// Pattern 11: Extract field references from new pattern
java.util.regex.Pattern newPattern = java.util.regex.Pattern.compile(
    "YourPatternHere", 
    java.util.regex.Pattern.MULTILINE | java.util.regex.Pattern.CASE_INSENSITIVE
);
```

## When to Use Each Approach

### Use Regex When:
- Pattern is common and well-defined
- Performance is critical
- Pattern is simple and deterministic
- You want to avoid API costs

### Rely on LLM When:
- Pattern is rare or unknown
- Structure is complex/nested
- Syntax has many variations
- You want adaptive extraction

## Monitoring

Check logs for extraction results:

```
DSL parsing complete (regex patterns) for file.aclf. Found X symbols: ...
LLM extraction found Y additional field references in file.aclf
Final parsing summary for file.aclf: Z total symbols
```

If LLM consistently finds many additional fields, consider:
1. Adding those patterns as regex patterns
2. Investigating why regex missed them
3. Updating regex patterns to catch them

## Troubleshooting

### LLM Extraction Failing

**Symptoms:**
- `LLM-based field extraction failed` in logs
- No additional fields extracted

**Causes:**
- LLM gateway unavailable
- Token limits exceeded
- Invalid JSON response from LLM
- Network issues

**Solution:**
- Check LLM gateway status
- Reduce `max-file-size` if files are too large
- Check LLM gateway logs
- Fallback to regex-only mode if needed

### Too Many False Positives

**Symptoms:**
- LLM extracting non-field identifiers
- Many irrelevant symbols

**Solution:**
- Refine LLM prompt (in `extractFieldsWithLLM()`)
- Add validation rules
- Use regex patterns for known good patterns
- Review and filter LLM results

### Performance Issues

**Symptoms:**
- Parsing taking too long
- High API costs

**Solution:**
- Disable LLM extraction (`enabled: false`)
- Reduce `max-file-size`
- Process files in batches
- Add more regex patterns to reduce LLM dependency

## Best Practices

1. **Start with hybrid mode** to discover all patterns
2. **Add regex patterns** for common patterns discovered by LLM
3. **Monitor extraction results** to identify patterns to add
4. **Disable LLM** for production if performance is critical
5. **Document new patterns** as you discover them
6. **Test regex patterns** before deploying to production

## Example: Pattern Discovery Workflow

1. **Initial parsing** with hybrid mode enabled
2. **Review logs** to see what LLM found that regex missed
3. **Analyze patterns** in LLM-extracted fields
4. **Add regex patterns** for common patterns
5. **Re-parse** to verify regex catches them
6. **Gradually reduce LLM dependency** as patterns are codified

This iterative approach ensures comprehensive extraction while optimizing for performance.
