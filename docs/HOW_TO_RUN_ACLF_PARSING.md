# How to Run ACLF Parsing

This guide explains how to run ACLF parsing with the new hybrid approach (regex + LLM extraction).

## Prerequisites

1. **Services Running:**
   - `code-parser` service
   - `llm-gateway-service` (if LLM extraction is enabled)
   - `postgres` database
   - `minio` storage

2. **ACLF Files Ingested:**
   - ACLF files should already be uploaded/ingested to MinIO
   - Projects should be registered in the database

## Step 1: Check Configuration

Verify LLM extraction is configured in `code-parser/src/main/resources/application.yaml`:

```yaml
parser:
  aclf:
    llm-extraction:
      enabled: true          # Set to false to use regex only
      max-file-size: 50000   # Adjust if needed
```

## Step 2: Trigger Parsing

### Option A: Automatic Parsing (During Ingestion)

If you upload a new project with ACLF files, parsing happens automatically during ingestion.

### Option B: Manual Parsing via API

Trigger parsing for a specific project:

```bash
# Get project ID first
curl http://localhost:8080/api/v1/projects

# Trigger parsing for a project
curl -X POST http://localhost:8080/api/v1/parser/trigger/<project_id>
```

### Option C: Re-parse All Projects

If you want to re-parse all projects (e.g., after code changes):

```bash
# Enable auto-parse on startup temporarily
# Edit code-parser/src/main/resources/application.yaml:
# parser.auto-parse-on-startup: true

# Restart code-parser service
docker-compose restart code-parser
```

**Note:** After restart, set `auto-parse-on-startup: false` again to prevent re-parsing on every startup.

## Step 3: Monitor Logs

Watch the `code-parser` service logs to see parsing progress:

```bash
# Docker Compose
docker-compose logs -f code-parser

# Or if using Kubernetes
kubectl logs -f deployment/code-parser
```

### What to Look For

**Successful Regex Parsing:**
```
INFO - Starting ACLF Parsing for legacy modernization: HSBKYCM.ACLF
INFO - ACLF file HSBKYCM.ACLF appears to be DSL format (not XML). Attempting DSL parsing...
INFO - DSL parsing complete (regex patterns) for HSBKYCM.ACLF. Found 25 symbols: 1 ExternalDatalists, 5 Datafields, 2 Transactions, 0 FormBlocks, 0 FormReports, 0 Calculations, 15 FieldReferences, 2 DatafieldReferences
```

**LLM Extraction (if enabled):**
```
INFO - LLM extraction found 8 additional field references in HSBKYCM.ACLF
INFO - Final parsing summary for HSBKYCM.ACLF: 33 total symbols (23 FieldReferences, 2 DatafieldReferences)
```

**Errors:**
```
WARN - LLM-based field extraction failed for file.aclf: <error message>. Continuing with regex-only results.
```

## Step 4: Verify Results in Database

### Check Symbols Extracted

```sql
-- Count ACLF symbols by category
SELECT category, COUNT(*) as count
FROM symbols s
JOIN source_files sf ON s.file_id = sf.id
WHERE sf.extension = '.aclf'
GROUP BY category
ORDER BY count DESC;
```

**Expected Categories:**
- `ACLF_EXTERNAL_DATALIST`
- `ACLF_DATAFIELD`
- `ACLF_TRANSACTION`
- `ACLF_FORM_BLOCK`
- `ACLF_FORM_REPORT`
- `ACLF_CALCULATION`
- `ACLF_FIELD_REFERENCE` (from regex + LLM)
- `ACLF_DATAFIELD_REFERENCE`

### Check Specific Field (e.g., BCUSTID)

```sql
-- Find BCUSTID field references
SELECT 
    s.name,
    s.category,
    s.data_type,
    sf.file_name,
    p.name as project_name
FROM symbols s
JOIN source_files sf ON s.file_id = sf.id
JOIN projects p ON sf.project_id = p.id
WHERE s.name = 'BCUSTID'
  AND sf.extension = '.aclf'
ORDER BY p.name, sf.file_name;
```

### Check Field References in Specific File

```sql
-- Check what fields were extracted from a specific ACLF file
SELECT 
    s.name,
    s.category,
    s.data_type,
    s.start_line
FROM symbols s
JOIN source_files sf ON s.file_id = sf.id
WHERE sf.file_name = 'HSBKYCM.ACLF'
ORDER BY s.category, s.name;
```

### Compare Regex vs LLM Extraction

```sql
-- Count symbols by extraction method (approximate)
-- Regex-extracted: Standard categories
-- LLM-extracted: Usually in ACLF_FIELD_REFERENCE with type containing "LLM-extracted"

SELECT 
    CASE 
        WHEN s.data_type LIKE '%LLM-extracted%' THEN 'LLM'
        ELSE 'Regex'
    END as extraction_method,
    COUNT(*) as count
FROM symbols s
JOIN source_files sf ON s.file_id = sf.id
WHERE sf.extension = '.aclf'
  AND s.category IN ('ACLF_FIELD_REFERENCE', 'ACLF_DATAFIELD_REFERENCE')
GROUP BY extraction_method;
```

## Step 5: Verify Vectorization

After parsing, symbols should be vectorized:

```bash
# Trigger vectorization for a project
curl -X POST http://localhost:8080/api/v1/vectorizer/trigger/<project_id>
```

Check vectorization logs:
```bash
docker-compose logs -f vectorizer-service
```

## Troubleshooting

### No Symbols Extracted

**Check:**
1. Are ACLF files in MinIO?
   ```bash
   # List files in MinIO
   docker-compose exec minio mc ls minio/decode-source-code/
   ```

2. Are files registered in database?
   ```sql
   SELECT COUNT(*) FROM source_files WHERE extension = '.aclf';
   ```

3. Check parsing logs for errors:
   ```bash
   docker-compose logs code-parser | grep -i "aclf\|error"
   ```

### LLM Extraction Not Working

**Check:**
1. Is LLM gateway available?
   ```bash
   curl http://localhost:8081/actuator/health
   ```

2. Check LLM extraction is enabled:
   ```yaml
   parser.aclf.llm-extraction.enabled: true
   ```

3. Check file size limits:
   ```yaml
   parser.aclf.llm-extraction.max-file-size: 50000
   ```

4. Review LLM gateway logs:
   ```bash
   docker-compose logs -f llm-gateway-service
   ```

### Performance Issues

**If parsing is too slow:**
1. Disable LLM extraction:
   ```yaml
   parser.aclf.llm-extraction.enabled: false
   ```

2. Process files in batches (parse specific projects only)

3. Increase file size limit if files are being skipped:
   ```yaml
   parser.aclf.llm-extraction.max-file-size: 100000
   ```

## Example: Complete Workflow

```bash
# 1. Check services are running
docker-compose ps

# 2. Find project ID
curl http://localhost:8080/api/v1/projects | jq '.[] | {id, name}'

# 3. Trigger parsing
PROJECT_ID="your-project-id"
curl -X POST http://localhost:8080/api/v1/parser/trigger/$PROJECT_ID

# 4. Monitor logs
docker-compose logs -f code-parser | grep -i "aclf"

# 5. Check results in database
psql -h localhost -U decode_user -d decode -c "
  SELECT category, COUNT(*) 
  FROM symbols s
  JOIN source_files sf ON s.file_id = sf.id
  WHERE sf.extension = '.aclf'
  GROUP BY category;
"

# 6. Verify specific field (e.g., BCUSTID)
psql -h localhost -U decode_user -d decode -c "
  SELECT s.name, sf.file_name, p.name as project
  FROM symbols s
  JOIN source_files sf ON s.file_id = sf.id
  JOIN projects p ON sf.project_id = p.id
  WHERE s.name = 'BCUSTID' AND sf.extension = '.aclf';
"

# 7. Trigger vectorization
curl -X POST http://localhost:8080/api/v1/vectorizer/trigger/$PROJECT_ID
```

## Performance Tips

1. **For 50K+ files:**
   - Start with LLM extraction enabled to discover all patterns
   - Review logs to see what patterns LLM found
   - Add regex patterns for common patterns
   - Gradually disable LLM as patterns are codified

2. **For production:**
   - Use regex-only mode (`enabled: false`) for speed
   - Re-enable LLM periodically to catch new patterns
   - Add new regex patterns as they're discovered

3. **For development:**
   - Keep LLM enabled to catch edge cases
   - Monitor extraction results
   - Document new patterns as you find them

## Next Steps

After parsing:
1. ✅ Verify symbols are in database
2. ✅ Check field references are extracted (e.g., BCUSTID)
3. ✅ Trigger vectorization
4. ✅ Test semantic search with extracted fields
5. ✅ Review logs to identify patterns for regex optimization
