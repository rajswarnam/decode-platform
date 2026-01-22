# How to Check if ExternalDatalist Symbols Are Indexed

This guide shows you how to verify that `ExternalDatalist` symbols are being extracted and stored in the database.

## Quick Check

### 1. Count All ExternalDatalist Symbols

```sql
-- Count total ExternalDatalist symbols
SELECT COUNT(*) as external_datalist_count
FROM symbols
WHERE category = 'ACLF_EXTERNAL_DATALIST';
```

### 2. List All ExternalDatalist Symbols

```sql
-- List all ExternalDatalist symbols with details
SELECT 
    s.id,
    s.name as datalist_name,
    s.type,
    s.start_line,
    sf.file_name as aclf_file,
    sf.file_path,
    p.name as project_name,
    p.id as project_id
FROM symbols s
JOIN source_files sf ON s.file_id = sf.id
JOIN projects p ON sf.project_id = p.id
WHERE s.category = 'ACLF_EXTERNAL_DATALIST'
ORDER BY p.name, sf.file_name, s.name;
```

### 3. Count ExternalDatalist by Project

```sql
-- Count ExternalDatalist symbols per project
SELECT 
    p.name as project_name,
    COUNT(s.id) as external_datalist_count
FROM symbols s
JOIN source_files sf ON s.file_id = sf.id
JOIN projects p ON sf.project_id = p.id
WHERE s.category = 'ACLF_EXTERNAL_DATALIST'
GROUP BY p.id, p.name
ORDER BY external_datalist_count DESC;
```

### 4. Count ExternalDatalist by ACLF File

```sql
-- Count ExternalDatalist symbols per ACLF file
SELECT 
    sf.file_name,
    sf.file_path,
    p.name as project_name,
    COUNT(s.id) as external_datalist_count
FROM symbols s
JOIN source_files sf ON s.file_id = sf.id
JOIN projects p ON sf.project_id = p.id
WHERE s.category = 'ACLF_EXTERNAL_DATALIST'
GROUP BY sf.id, sf.file_name, sf.file_path, p.name
ORDER BY external_datalist_count DESC;
```

### 5. Check for Specific ExternalDatalist Name

```sql
-- Search for a specific ExternalDatalist by name
SELECT 
    s.name as datalist_name,
    s.type,
    sf.file_name as aclf_file,
    p.name as project_name
FROM symbols s
JOIN source_files sf ON s.file_id = sf.id
JOIN projects p ON sf.project_id = p.id
WHERE s.category = 'ACLF_EXTERNAL_DATALIST'
  AND s.name LIKE '%<your_search_term>%'  -- Replace with actual name
ORDER BY p.name, sf.file_name;
```

## Comprehensive ACLF Symbol Breakdown

### All ACLF Categories

```sql
-- Count all ACLF symbol categories
SELECT 
    category,
    COUNT(*) as count,
    COUNT(DISTINCT sf.id) as file_count,
    COUNT(DISTINCT p.id) as project_count
FROM symbols s
JOIN source_files sf ON s.file_id = sf.id
JOIN projects p ON sf.project_id = p.id
WHERE s.category LIKE 'ACLF%'
GROUP BY category
ORDER BY count DESC;
```

**Expected Categories:**
- `ACLF_EXTERNAL_DATALIST` - ExternalDatalist definitions
- `ACLF_DATAFIELD` - Datafield definitions
- `ACLF_TRANSACTION` - Transaction definitions
- `ACLF_TRANSACTION_REFERENCE` - Transaction references
- `ACLF_FORM_BLOCK` - FormBlock definitions
- `ACLF_FORM_REPORT` - FormReport definitions
- `ACLF_CALCULATION` - Calculation definitions
- `ACLF_FIELD_REFERENCE` - Field references
- `ACLF_DATAFIELD_REFERENCE` - Datafield references
- `ACLF_TAG` - XML format tags

## Check for a Specific Project/Group

### For "fusion" Group

```sql
-- Count ExternalDatalist for fusion projects
SELECT 
    p.name as project_name,
    COUNT(s.id) as external_datalist_count
FROM symbols s
JOIN source_files sf ON s.file_id = sf.id
JOIN projects p ON sf.project_id = p.id
WHERE s.category = 'ACLF_EXTERNAL_DATALIST'
  AND (p.name LIKE '%fusion%' OR p.base_path LIKE '%fusion%')
GROUP BY p.id, p.name
ORDER BY external_datalist_count DESC;
```

## Verify Parsing Logs

### Check if ExternalDatalist Pattern Matched

Look for these log messages in `code-parser` service:

**Successful Match:**
```
Found ExternalDatalist: <datalist_name>
DSL parsing complete for <file.aclf>. Found X symbols: Y ExternalDatalists, ...
```

**No Match:**
```
No ExternalDatalist definitions found in <file.aclf> (searched for pattern: ExternalDatalist <name> {)
```

### Check Docker Logs

```bash
# View code-parser logs
docker-compose logs -f code-parser | grep -i "externaldatalist"

# Or view all ACLF parsing logs
docker-compose logs -f code-parser | grep -i "aclf"
```

## Troubleshooting

### If You See 0 ExternalDatalist Symbols

1. **Check if ACLF files were ingested:**
   ```sql
   SELECT COUNT(*) 
   FROM source_files 
   WHERE extension = '.aclf';
   ```

2. **Check if files are DSL format (not XML):**
   - DSL format files should contain `ExternalDatalist` keyword
   - XML format files start with `<` and won't have ExternalDatalist symbols

3. **Check parsing logs for pattern matching:**
   - Look for: `No ExternalDatalist definitions found`
   - This means the regex pattern didn't match the file format

4. **Verify the regex pattern matches your file format:**
   - Pattern: `ExternalDatalist\s+(\w+)\s*\{`
   - Example match: `ExternalDatalist A2AIMGO {`
   - If your files use a different format, the pattern won't match

5. **Check if parsing actually ran:**
   ```sql
   -- Check if any symbols exist for ACLF files
   SELECT COUNT(*) 
   FROM symbols s
   JOIN source_files sf ON s.file_id = sf.id
   WHERE sf.extension = '.aclf';
   ```

## Example Queries for Common Scenarios

### Find All ExternalDatalist Names

```sql
SELECT DISTINCT s.name as datalist_name
FROM symbols s
WHERE s.category = 'ACLF_EXTERNAL_DATALIST'
ORDER BY s.name;
```

### Check ExternalDatalist with Field References

```sql
-- Find ExternalDatalist and their associated field references
SELECT 
    ed.name as external_datalist,
    ed.type,
    fr.name as field_reference,
    fr.type as field_type,
    sf.file_name
FROM symbols ed
JOIN source_files sf ON ed.file_id = sf.id
LEFT JOIN symbols fr ON fr.file_id = ed.file_id 
    AND fr.category = 'ACLF_FIELD_REFERENCE'
    AND fr.type LIKE '%' || ed.name || '%'
WHERE ed.category = 'ACLF_EXTERNAL_DATALIST'
ORDER BY ed.name, fr.name;
```

### Verify Vectorization Status

```sql
-- Check if ExternalDatalist symbols are vectorized
-- (This requires checking the vector store, but you can verify symbols exist)
SELECT 
    COUNT(*) as total_external_datalists,
    COUNT(DISTINCT sf.project_id) as projects_with_datalists
FROM symbols s
JOIN source_files sf ON s.file_id = sf.id
WHERE s.category = 'ACLF_EXTERNAL_DATALIST';
```

## Quick Verification Script

Run this to get a complete picture:

```sql
-- Complete ExternalDatalist verification
SELECT 
    'ExternalDatalist Symbols' as check_type,
    COUNT(*) as total_count,
    COUNT(DISTINCT sf.id) as aclf_files_with_datalists,
    COUNT(DISTINCT p.id) as projects_with_datalists
FROM symbols s
JOIN source_files sf ON s.file_id = sf.id
JOIN projects p ON sf.project_id = p.id
WHERE s.category = 'ACLF_EXTERNAL_DATALIST';

-- Top 10 ExternalDatalist names
SELECT 
    s.name,
    COUNT(*) as occurrence_count
FROM symbols s
WHERE s.category = 'ACLF_EXTERNAL_DATALIST'
GROUP BY s.name
ORDER BY occurrence_count DESC
LIMIT 10;
```
