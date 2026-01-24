# Troubleshooting: No Results from Vector Search

## Problem: Query Returns 0 Results

When you ask a question like "I am trying to understand variable involved in EBKYCIP transaction" but get:
- "0 FILES ANALYZED"
- "0.0/10 EVIDENCE QUALITY"
- "No relevant code found"
- "Filtered all documents! No documents matched project"

## Root Causes

### 1. **Projects Not Vectorized Yet** ⚠️ MOST COMMON

**Symptoms:**
- Logs show: "Filtered 10 documents to 0 matching project"
- "0 FILES ANALYZED" in results
- Projects were parsed but not vectorized

**Check:**
```sql
-- Check if symbols exist in database
SELECT COUNT(*) FROM symbols 
WHERE source_file_id IN (
    SELECT id FROM source_files 
    WHERE project_id IN (
        SELECT id FROM projects WHERE name LIKE '%fusion%'
    )
);
```

**Solution:**
- Trigger vectorization for the projects:
```bash
curl -X POST "http://localhost:8080/api/vectorizer/trigger?projectId={projectId}"
```

Or check vectorizer status:
```bash
curl "http://localhost:8080/api/vectorizer/status?projectId={projectId}"
```

### 2. **Project ID Mismatch**

**Symptoms:**
- Logs show: "Found project_ids in metadata: [uuid1, uuid2], Looking for: [uuid3]"
- Documents exist in Qdrant but have different project_ids

**Check:**
```sql
-- Get project IDs for selected projects
SELECT id, name FROM projects WHERE name IN ('fusion-master (1)/fusion-master/Argo/Common/Group/K/Transaction', ...);
```

**Solution:**
- Verify project names match exactly between:
  - Database (`projects` table)
  - UI selection
  - Qdrant metadata (`domain` field)

### 3. **No Documents in Qdrant**

**Symptoms:**
- "Test search for 'service' returned 0 documents"
- Vector store appears empty

**Check:**
- Verify Qdrant is running and accessible
- Check if any projects have been vectorized:
```bash
# Check vectorizer logs
docker logs vectorizer-service-1 | grep "Successfully vectorized"
```

**Solution:**
- Re-vectorize projects
- Check Qdrant connection and collection name

### 4. **Search Query Too Specific**

**Symptoms:**
- Query like "EBKYCIP transaction" doesn't match any embeddings
- More generic queries work

**Solution:**
- Try broader queries first: "transaction", "EBKYCIP", "variables"
- The system uses semantic search, so exact matches aren't required
- But if the term doesn't exist in the codebase, it won't find anything

### 5. **Project Filtering Too Strict**

**Symptoms:**
- Logs show documents found but filtered to 0
- "Filtered 10 documents to 0 matching project"

**Check:**
- Verify project IDs are being resolved correctly:
```java
// In SemanticExplorerController, check:
log.info("Multi-project query: {} projects selected ({} IDs resolved)", 
    projectNamesList.size(), projectIdsList.size());
```

**Solution:**
- If `projectIdsList.size() == 0`, the project names don't match database
- Check project names in database vs UI selection

## Diagnostic Steps

### Step 1: Check if Projects Are Vectorized

```bash
# Check vectorizer status for a project
curl "http://localhost:8080/api/vectorizer/status?projectId={projectId}"
```

Expected response should show symbols vectorized.

### Step 2: Check Database for Symbols

```sql
-- Count symbols for selected projects
SELECT p.name, COUNT(s.id) as symbol_count
FROM projects p
LEFT JOIN source_files sf ON sf.project_id = p.id
LEFT JOIN symbols s ON s.source_file_id = sf.id
WHERE p.name LIKE '%fusion%'
GROUP BY p.name;
```

If `symbol_count = 0`, parsing didn't extract symbols.

### Step 3: Check Qdrant Documents

If you have access to Qdrant directly:
```python
# Python example
from qdrant_client import QdrantClient

client = QdrantClient(host="localhost", port=6333)
results = client.scroll(
    collection_name="symbols",
    limit=10
)
for point in results[0]:
    print(f"Project ID: {point.payload.get('project_id')}")
    print(f"Domain: {point.payload.get('domain')}")
```

### Step 4: Check Logs for Project ID Resolution

Look for these log messages:
```
Multi-project query: 20 projects selected (20 IDs resolved)
```

If IDs resolved < projects selected, some project names don't match.

### Step 5: Test Unfiltered Search

Temporarily disable project filtering to see if documents exist:
```java
// In AgentOrchestrator, comment out project filtering
// results = filterByProjectId(results, projectIds);
```

If unfiltered search returns results, the issue is project ID matching.

## Common Fixes

### Fix 1: Re-vectorize Projects

```bash
# For a single project
curl -X POST "http://localhost:8080/api/vectorizer/trigger?projectId={projectId}"

# For all projects (if endpoint exists)
curl -X POST "http://localhost:8080/api/vectorizer/trigger/all"
```

### Fix 2: Re-parse Projects

If symbols don't exist in database:
```bash
# Trigger parser for a project
curl -X POST "http://localhost:8080/api/v1/parser/trigger?projectId={projectId}"

# Or for a group
curl -X POST "http://localhost:8080/api/v1/parser/trigger/group?groupName=fusion"
```

### Fix 3: Verify Project Names Match

```sql
-- List all project names
SELECT name FROM projects ORDER BY name;

-- Compare with what's selected in UI
```

### Fix 4: Check Domain vs Project Name

The system uses both `domain` and `project_id` for filtering. Check:
```sql
SELECT id, name, domain FROM projects WHERE name LIKE '%fusion%';
```

If `domain` is NULL or different from `name`, filtering might fail.

## Prevention

1. **Always verify vectorization after parsing:**
   - Check vectorizer logs after parsing completes
   - Verify symbols are in Qdrant before querying

2. **Use consistent naming:**
   - Project names in database should match UI selection
   - Domain field should match project name (or be set correctly)

3. **Monitor project ID resolution:**
   - Check logs for "X projects selected (Y IDs resolved)"
   - If Y < X, investigate name mismatches

4. **Test with generic queries first:**
   - Try "transaction" before "EBKYCIP transaction"
   - Verify vector search is working before using specific terms

## Quick Checklist

- [ ] Projects have been parsed (symbols in database)
- [ ] Projects have been vectorized (symbols in Qdrant)
- [ ] Project names in UI match database
- [ ] Project IDs are being resolved correctly
- [ ] Qdrant is accessible and has documents
- [ ] Search query isn't too specific/non-existent term
- [ ] Project filtering isn't too strict

## Example: Debugging "EBKYCIP transaction" Query

1. **Check if "EBKYCIP" exists in codebase:**
   ```sql
   SELECT * FROM symbols WHERE name LIKE '%EBKYCIP%' OR name LIKE '%ebkycip%';
   ```

2. **Check if transaction-related symbols exist:**
   ```sql
   SELECT * FROM symbols WHERE category LIKE '%TRANSACTION%' LIMIT 10;
   ```

3. **Check if selected projects have these symbols:**
   ```sql
   SELECT s.name, s.category, p.name as project_name
   FROM symbols s
   JOIN source_files sf ON s.source_file_id = sf.id
   JOIN projects p ON sf.project_id = p.id
   WHERE (s.name LIKE '%EBKYCIP%' OR s.category LIKE '%TRANSACTION%')
     AND p.name IN ('fusion-master (1)/fusion-master/Argo/Common/Group/K/Transaction', ...);
   ```

4. **If symbols exist but query fails:**
   - Check if they're vectorized (Qdrant)
   - Check if project IDs match
   - Try broader query: "transaction" or "EBKYCIP"
