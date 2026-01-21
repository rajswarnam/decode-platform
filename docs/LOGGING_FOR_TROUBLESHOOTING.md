# Logging for Troubleshooting Fluff Responses

## Current Logging (What We Have)

### ✅ 1. Query Intent & Planning
- `Query Intent: mode={}, topK={}, reasoning={}` - Shows analysis mode
- `Project tech stack: {}` - Shows detected technologies
- `Starting Agent Swarm for: {}` - User query

### ✅ 2. Vector Search Results
- `Worker {} searching for: {} (mode: {}, topK: {})` - Search query and parameters
- `Vector search returned {} documents for worker {} (mode: {})` - Number of results
- `⚠️ No documents found for query: '{}' with domain: '{}'` - Empty results warning
- `⚠️ Test search for 'service' returned {} documents` - Vector store health check
- `Retrieved {} unique files for worker {}` - Files retrieved (DEBUG level)

### ✅ 3. Coverage & QA Issues
- `File coverage too low: {} files vs {} recommended` - Coverage warnings
- `Actual repository coverage low: {}%` - Repository coverage
- `QA report shows no issues. All workers satisfied.` - QA status

### ✅ 4. Synthesis Status
- `Synthesis returned empty result` - Warning when synthesis fails

### ✅ 5. Worker Execution
- `Task failed` - Worker execution errors
- `Invalid persona in plan` - Worker assignment issues

---

## Missing Logging (What We Need)

### ❌ 1. Worker Report Content
**Missing**: What workers actually report
- No logging of worker report summaries
- No logging of evidence count in worker reports
- No logging when workers report "NO EVIDENCE FOUND"

**Impact**: Can't tell if workers are generating fluff or if synthesis is failing

### ❌ 2. Evidence Extraction
**Missing**: Evidence details from worker reports
- No logging of how many evidence files were extracted
- No logging of evidence file paths
- No logging when evidence pattern matching fails

**Impact**: Can't verify if workers found actual code evidence

### ❌ 3. Synthesis Input
**Missing**: What's being sent to synthesis
- No logging of worker reports being sent to synthesis
- No logging of synthesis prompt (or even a summary)
- No logging of input size (token count) for synthesis

**Impact**: Can't see what context synthesis is working with

### ❌ 4. Worker Task Details
**Missing**: Detailed worker execution info
- No logging of worker questions after refinement
- No logging of worker persona and focus area
- No logging of context size sent to each worker

**Impact**: Hard to debug which workers are producing fluff

---

## Recommended Additional Logging

### 1. Enhanced Worker Report Logging

```java
// After worker completes
log.info("Worker {} completed - Report length: {} chars, Evidence count: {}", 
    task.getPersona(), 
    task.getReport() != null ? task.getReport().length() : 0,
    extractEvidenceCount(task.getReport()));

// If no evidence found
if (report != null && report.contains("NO EVIDENCE FOUND")) {
    log.warn("⚠️ Worker {} found NO EVIDENCE for question: {}", 
        task.getPersona(), task.getSpecificQuestion());
}
```

### 2. Evidence Extraction Logging

```java
// In refineTasksBasedOnQA or similar
Set<String> evidenceFiles = extractEvidenceFiles(tasks);
log.info("Evidence extraction: {} evidence files found from {} workers", 
    evidenceFiles.size(), tasks.size());
log.debug("Evidence files: {}", evidenceFiles);
```

### 3. Synthesis Input Logging

```java
// In synthesizeResults
log.info("Synthesis input: {} worker reports, total length: {} chars", 
    results.size(), inputs.length());
log.debug("Synthesis prompt length: {} chars, intent: {}", 
    prompt.length(), intent);
log.debug("Worker reports summary: {}", 
    results.stream()
        .map(t -> t.getPersona() + ": " + 
            (t.getReport() != null ? t.getReport().substring(0, Math.min(100, t.getReport().length())) : "null"))
        .collect(Collectors.joining(", ")));
```

### 4. Worker Context Logging

```java
// In executeTaskWithType, before LLM call
log.debug("Worker {} context: {} files, {} chars, question: {}", 
    task.getPersona(), 
    context.split("--- File:").length - 1,
    context.length(),
    task.getSpecificQuestion());
```

---

## Quick Debug Commands

### Check Worker Reports in Logs
```bash
# Look for worker completion
grep "Worker.*completed" logs/context-orchestrator.log

# Look for evidence warnings
grep "NO EVIDENCE" logs/context-orchestrator.log

# Look for vector search results
grep "Vector search returned" logs/context-orchestrator.log
```

### Check Synthesis Input
```bash
# Look for synthesis start
grep "Generating Final Strategic Document" logs/context-orchestrator.log

# Look for synthesis failures
grep "Synthesis returned empty" logs/context-orchestrator.log
```

### Check Coverage Issues
```bash
# Look for coverage warnings
grep "File coverage too low\|Actual repository coverage" logs/context-orchestrator.log
```

### Check Vector Store Health
```bash
# Look for empty results
grep "No documents found" logs/context-orchestrator.log

# Look for vector store test
grep "Test search for 'service'" logs/context-orchestrator.log
```

---

## What to Check When Debugging Fluff Responses

1. **Check Vector Search Results** ✅
   ```bash
   grep "Vector search returned.*documents" logs/context-orchestrator.log | tail -10
   ```
   - If 0 documents: Vector store empty or query too specific
   - If many documents: Check if they're relevant

2. **Check Worker Reports** ⚠️ (Limited logging)
   - Currently only logs completion, not report content
   - Need to check execution plan directly or enhance logging

3. **Check Evidence Count** ❌ (Not logged)
   - Currently not logged
   - Need to add logging or check QA reports

4. **Check Synthesis Input** ❌ (Not logged)
   - Currently not logged
   - Need to enable DEBUG temporarily or add INFO logging

---

## Recommendation

**Enable DEBUG logging temporarily** for troubleshooting:
```yaml
logging:
  level:
    com.decode.context.orchestrator.agent.AgentOrchestrator: DEBUG
```

This will show:
- Individual files retrieved: `Added file: {} (size: {} bytes)`
- Unique files per worker: `Retrieved {} unique files for worker {}`

Then check:
- Are workers getting code context?
- Are worker reports empty or generic?
- Is synthesis getting valid input?

---

## Next Steps

1. **Immediate**: Enable DEBUG logging to see file retrieval details
2. **Short-term**: Add evidence extraction logging
3. **Medium-term**: Add worker report summary logging
4. **Long-term**: Add synthesis input logging (with size limits)
