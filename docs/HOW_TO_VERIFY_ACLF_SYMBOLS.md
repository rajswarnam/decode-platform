# How to Verify ACLF Symbols Are Generated

This guide explains how to verify that symbols are being generated for ACLF files during parsing.

## 1. Check Log Messages

### During Parsing

Look for these log messages in the `code-parser` service:

**ACLF File Detection:**
```
Starting ACLF Parsing for legacy modernization: <filename.aclf>
```

**Format Detection:**
- XML format: `ACLF file <filename> appears to be XML format`
- DSL format: `ACLF file <filename> appears to be DSL format (not XML). Attempting DSL parsing...`

**DSL Parsing Results:**
```
DSL parsing complete for <filename>. Found X symbols: Y ExternalDatalists, Z Datafields, ...
```

**Symbol Saving:**
```
✅ Saved X symbols for <storage_key> (Project: <project_name>)
```

**ACLF Binding (XML format):**
```
Discovered ACLF Detail: Tag=<tag>, Data=<data>
ACLF Binding SUCCESS (EXACT): <tag> -> <data>
```

### Error Messages

If ACLF files fail to parse, you'll see:
- `ACLF file <filename> is empty, skipping`
- `ACLF file <filename> has invalid XML structure (WstxUnexpectedCharException)`
- `Failed to parse ACLF file as XML: <filename>`

## 2. Database Queries

### Check Symbols by Category

ACLF symbols have categories starting with `ACLF_`:

```sql
-- Count ACLF symbols by category
SELECT category, COUNT(*) as count
FROM symbols
WHERE category LIKE 'ACLF%'
GROUP BY category
ORDER BY count DESC;
```

**Expected Categories:**
- `ACLF_TAG` - XML format tags
- `ACLF_EXTERNAL_DATALIST` - ExternalDatalist definitions (DSL)
- `ACLF_DATAFIELD` - Datafield definitions (DSL)
- `ACLF_TRANSACTION` - Transaction definitions (DSL)
- `ACLF_FORM_BLOCK` - FormBlock definitions (DSL)
- `ACLF_FORM_REPORT` - FormReport definitions (DSL)
- `ACLF_CALCULATION` - Calculation definitions (DSL)

### Check ACLF Source Files

```sql
-- Find all ACLF source files
SELECT sf.id, sf.file_path, sf.file_name, sf.extension, p.name as project_name
FROM source_files sf
JOIN projects p ON sf.project_id = p.id
WHERE sf.extension = '.aclf'
ORDER BY p.name, sf.file_name;
```

### Count Symbols per ACLF File

```sql
-- Count symbols per ACLF file
SELECT 
    sf.file_name,
    sf.file_path,
    p.name as project_name,
    COUNT(s.id) as symbol_count
FROM source_files sf
JOIN projects p ON sf.project_id = p.id
LEFT JOIN symbols s ON s.file_id = sf.id
WHERE sf.extension = '.aclf'
GROUP BY sf.id, sf.file_name, sf.file_path, p.name
ORDER BY symbol_count DESC;
```

### Check ACLF Mappings (XML format)

For XML format ACLF files, check the `aclf_mappings` table:

```sql
-- View ACLF mappings (business tags to symbols)
SELECT 
    am.business_tag,
    am.mapping_strategy,
    am.confidence_score,
    s.name as symbol_name,
    s.category as symbol_category,
    sf.file_name as aclf_file,
    p.name as project_name
FROM aclf_mappings am
LEFT JOIN symbols s ON am.symbol_id = s.id
LEFT JOIN source_files sf ON am.aclf_file_id = sf.id
LEFT JOIN projects p ON am.project_id = p.id
ORDER BY am.confidence_score DESC, p.name, sf.file_name;
```

### Check Symbols for a Specific Project

```sql
-- Count ACLF symbols for a specific project
SELECT 
    category,
    COUNT(*) as count
FROM symbols s
JOIN source_files sf ON s.file_id = sf.id
JOIN projects p ON sf.project_id = p.id
WHERE p.name = '<your_project_name>'
  AND s.category LIKE 'ACLF%'
GROUP BY category
ORDER BY count DESC;
```

## 3. API Endpoints

### Check Parsing Status

```bash
# Check if a project has been parsed
curl http://localhost:8080/api/v1/parser/status/<project_id>
```

### Trigger Parsing (if needed)

```bash
# Manually trigger parsing for a project
curl -X POST http://localhost:8080/api/v1/parser/trigger/<project_id>
```

## 4. Quick Verification Script

Create a simple SQL script to verify ACLF parsing:

```sql
-- Quick ACLF verification query
SELECT 
    'ACLF Files' as check_type,
    COUNT(DISTINCT sf.id) as file_count,
    COUNT(s.id) as symbol_count,
    COUNT(DISTINCT p.id) as project_count
FROM source_files sf
JOIN projects p ON sf.project_id = p.id
LEFT JOIN symbols s ON s.file_id = sf.id
WHERE sf.extension = '.aclf';

-- Breakdown by category
SELECT 
    s.category,
    COUNT(*) as count,
    COUNT(DISTINCT sf.id) as file_count
FROM symbols s
JOIN source_files sf ON s.file_id = sf.id
WHERE s.category LIKE 'ACLF%'
GROUP BY s.category
ORDER BY count DESC;
```

## 5. Common Issues

### No Symbols Found

If you see 0 symbols for ACLF files:

1. **Check if files were ingested:**
   ```sql
   SELECT COUNT(*) FROM source_files WHERE extension = '.aclf';
   ```

2. **Check if parsing ran:**
   - Look for log messages: `Starting ACLF Parsing for legacy modernization`
   - Check if `code-parser` service processed the files

3. **Check for parsing errors:**
   - Look for `WstxUnexpectedCharException` or `JsonParseException` in logs
   - These indicate the file format might not be valid XML

4. **Check file format:**
   - XML format: Should start with `<`
   - DSL format: Should contain keywords like `ExternalDatalist`, `Datafield`, `Transaction`

### Symbols Exist But Not Vectorized

If symbols exist in database but aren't in vector store:

1. **Check vectorization status:**
   ```sql
   SELECT COUNT(*) FROM symbols s
   JOIN source_files sf ON s.file_id = sf.id
   WHERE sf.extension = '.aclf'
     AND s.category LIKE 'ACLF%';
   ```

2. **Trigger vectorization:**
   ```bash
   curl -X POST http://localhost:8080/api/v1/vectorizer/trigger/<project_id>
   ```

## 6. Expected Results

After successful parsing, you should see:

- **For XML format ACLF files:**
  - Symbols with category `ACLF_TAG`
  - Entries in `aclf_mappings` table
  - Log messages showing "ACLF Binding SUCCESS"

- **For DSL format ACLF files:**
  - Symbols with categories: `ACLF_EXTERNAL_DATALIST`, `ACLF_DATAFIELD`, `ACLF_TRANSACTION`, etc.
  - Log message showing count of each symbol type
  - No entries in `aclf_mappings` (only for XML format)

## 7. Example Output

**Successful DSL Parsing:**
```
INFO  - Parsing DSL-format ACLF file: example.aclf
INFO  - DSL parsing complete for example.aclf. Found 15 symbols: 2 ExternalDatalists, 5 Datafields, 3 Transactions, 2 FormBlocks, 2 FormReports, 1 Calculations
INFO  - ✅ Saved 15 symbols for fusion-master/Argo/example.aclf (Project: fusion-master)
```

**Successful XML Parsing:**
```
INFO  - Starting ACLF Parsing for legacy modernization: config.aclf
INFO  - ACLF file config.aclf appears to be XML format
INFO  - Discovered ACLF Detail: Tag=CUSTOMER_NAME, Data=customerName
INFO  - ACLF Binding SUCCESS (EXACT): CUSTOMER_NAME -> customerName
INFO  - ✅ Saved 10 symbols for fusion-master/Argo/config.aclf (Project: fusion-master)
```
