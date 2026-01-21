# Debugging Fluff Responses

## Issue
User prompts are returning fluff/nonsensical responses instead of substantive analysis based on codebase.

## Common Causes & Solutions

### 1. **Worker Reports Are Empty or Generic**
**Symptom**: Synthesis shows "typical business use cases" without specific evidence.

**Root Cause**:
- Workers found no evidence in the codebase
- Vector search returned no relevant results
- Workers generated generic responses without code context

**Check**:
- Review worker reports in execution plan
- Check if `**Evidence**:` sections are empty or missing
- Verify vector search returned results for the query

**Fix**:
- Ensure projects are properly ingested and vectorized
- Check if project selection includes relevant code
- Verify tech stack detection matches actual files

### 2. **Prompt Truncation**
**Symptom**: Responses are cut off mid-sentence or incomplete.

**Root Cause**:
- Prompt exceeds 120k token limit
- Worker reports are too verbose
- Context is too large

**Check**:
- Check logs for "Token count exceeds limit" warnings
- Review prompt size in logs (if enabled)

**Fix**:
- Reduce `max-input-tokens` if needed
- Limit worker report length
- Use more focused queries

### 3. **Data Privacy Filter Removing Too Much**
**Symptom**: Responses mention "[REDACTED]" frequently or context is missing.

**Root Cause**:
- Sensitive data patterns matching code content (e.g., dates like "20231031")
- Filter too aggressive for this codebase

**Check**:
- Review logs for "Data Privacy Filter: Redacted X sensitive data pattern(s)"
- Check if legitimate code content is being redacted

**Fix**:
- Adjust data privacy filter patterns in `application.yaml`
- Disable filter temporarily to test: `llm.privacy.filter.enabled: false`

### 4. **Worker Reports Lack Substance**
**Symptom**: Workers complete but reports are generic ("no specific workflows identified").

**Root Cause**:
- Vector search returned irrelevant results
- Code not properly parsed/vectorized
- Workers not finding relevant code

**Check**:
```sql
-- Check if symbols were vectorized for the project
SELECT COUNT(*) FROM symbols WHERE source_file_id IN (
    SELECT id FROM source_files WHERE project_id = '<your-project-id>'
);
```

**Fix**:
- Verify ingestion completed successfully
- Check if code parser found symbols
- Verify vectorizer service ran
- Re-run ingestion if needed

### 5. **Multi-Project Selection Issues**
**Symptom**: Selecting multiple projects causes confusion in responses.

**Root Cause**:
- Context mixing from unrelated projects
- Domain filtering not working correctly

**Fix**:
- Try single project selection first
- Verify domain grouping is correct
- Check if query intent is mixing domains

### 6. **Intent Detection Wrong**
**Symptom**: Business query returns technical analysis or vice versa.

**Root Cause**:
- Intent detection misclassifying query
- Wrong prompt template used

**Check**:
- Look for "INTENT_DETECTED:" in logs
- Verify prompt template matches query type

**Fix**:
- Use explicit keywords in query:
  - Business: "business overview", "use cases", "functional analysis"
  - Technical: "code quality", "performance", "SRE analysis"

### 7. **Vector Search Quality**
**Symptom**: Workers reference irrelevant or wrong code.

**Root Cause**:
- Poor similarity search results
- Embeddings not capturing code semantics
- Wrong search query formulation

**Check**:
- Review worker evidence files - do they match the query?
- Check vector search topK and filters

**Fix**:
- Improve query formulation for workers
- Adjust similarity search parameters
- Re-vectorize if embeddings seem poor

## Debugging Steps

### Step 1: Check Worker Reports
```bash
# Check execution plan logs for worker reports
grep "WorkerTask" logs | grep -i "report"
```

Look for:
- Empty reports
- Generic responses
- Missing evidence sections

### Step 2: Verify Data Ingestion
```sql
-- Check if project has symbols
SELECT p.name, COUNT(s.id) as symbol_count
FROM projects p
LEFT JOIN source_files sf ON sf.project_id = p.id
LEFT JOIN symbols s ON s.source_file_id = sf.id
GROUP BY p.name;

-- Check if symbols were vectorized
SELECT COUNT(*) FROM symbols WHERE analysis_status = 'PENDING';
```

### Step 3: Test Vector Search
```bash
# Test vector search directly via API
curl -X POST http://localhost:8082/api/v1/explore/query \
  -H "Content-Type: application/json" \
  -d '{"query": "EBKYCIPX Business Customer Profile KYC", "domain": "fusion"}'
```

### Step 4: Check Logs for Warnings
```bash
# Look for truncation warnings
grep "Token count exceeds limit" logs/llm-gateway-service.log

# Look for data privacy warnings
grep "Data Privacy Filter" logs/llm-gateway-service.log

# Look for empty worker reports
grep "No evidence" logs/context-orchestrator.log
```

### Step 5: Review Synthesis Prompt
The synthesis prompt in `AgentOrchestrator.synthesizeResults()` uses worker reports. If worker reports are empty/generic, the synthesis will also be fluff.

**Key Prompt Sections**:
- Business queries: Focuses on "WHAT the system does"
- Technical queries: Focuses on "HOW it's implemented"

**Check if prompt is getting the right worker reports**.

## Quick Fixes

### Fix 1: Reduce Log Noise
Already done: Changed `InternalLlmClientService` logging from `DEBUG` to `WARN` to eliminate verbose chunk logs.

### Fix 2: Check Worker Reports First
Before investigating synthesis, verify worker reports contain actual evidence.

### Fix 3: Use Focused Queries
Instead of broad queries like "what are variables related to X", try:
- "Find the EBKYCIPX class definition and its member variables"
- "Show me the KYC inquiry workflow implementation"
- "What business logic handles Business Customer Profile"

### Fix 4: Verify Project Selection
Ensure you're selecting projects that actually contain the code you're querying:
- For Fusion project, ensure relevant sub-projects are selected
- Check domain grouping matches your query

### Fix 5: Re-run Ingestion if Needed
If symbols are missing or vectorization incomplete:
```bash
# Trigger re-parsing
curl -X POST http://localhost:8080/api/v1/parser/trigger?project=<project-name>

# Trigger re-vectorization
curl -X POST http://localhost:8080/api/v1/vectorizer/trigger?project=<project-name>
```

## Next Steps

1. **Immediate**: Check worker reports in the execution plan to see if they have evidence
2. **Check**: Verify the project has symbols and they're vectorized
3. **Test**: Try a more focused query to see if it improves
4. **Review**: Look at the actual prompt sent to LLM (if you enable DEBUG temporarily)
