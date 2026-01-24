# Checking and Cleaning Up Duplicate Symbols

## Did We Create Duplicate Symbols?

**Yes, likely.** Before the duplicate checking was added, the code had this comment:
```java
// Check if symbol exists to avoid duplicates? Ideally yes.
// For now, simple insert
```

This means symbols were being inserted without checking for duplicates, so if you re-parsed the same files, duplicate symbols were likely created.

## How to Check for Duplicates

### SQL Query to Find Duplicate Symbols

Run this query to see how many duplicates exist:

```sql
-- Find symbols that appear multiple times with same name, category, file, and line
SELECT 
    s.name,
    s.category,
    sf.file_path,
    s.start_line,
    COUNT(*) as duplicate_count
FROM symbols s
JOIN source_files sf ON s.file_id = sf.id
GROUP BY s.name, s.category, sf.id, s.start_line
HAVING COUNT(*) > 1
ORDER BY duplicate_count DESC;
```

### Count Total Duplicates

```sql
-- Count total duplicate symbols (excluding the first occurrence of each)
SELECT 
    COUNT(*) - COUNT(DISTINCT (s.name, s.category, sf.id, s.start_line)) as total_duplicates
FROM symbols s
JOIN source_files sf ON s.file_id = sf.id;
```

### Find Duplicates by Project

```sql
-- Find duplicates for a specific project
SELECT 
    p.name as project_name,
    s.name as symbol_name,
    s.category,
    sf.file_path,
    s.start_line,
    COUNT(*) as duplicate_count
FROM symbols s
JOIN source_files sf ON s.file_id = sf.id
JOIN projects p ON sf.project_id = p.id
WHERE p.name LIKE '%fusion%'  -- Change to your project name
GROUP BY p.name, s.name, s.category, sf.id, s.start_line
HAVING COUNT(*) > 1
ORDER BY duplicate_count DESC;
```

## How to Clean Up Duplicates

### Option 1: Keep the Oldest Symbol (Recommended)

This keeps the first symbol created and deletes the duplicates:

```sql
-- PostgreSQL: Delete duplicates, keeping the one with the smallest ID (oldest)
DELETE FROM symbols
WHERE id IN (
    SELECT id
    FROM (
        SELECT id,
               ROW_NUMBER() OVER (
                   PARTITION BY name, category, file_id, start_line 
                   ORDER BY id
               ) as rn
        FROM symbols
    ) t
    WHERE rn > 1
);
```

### Option 2: Keep the Newest Symbol

If you want to keep the most recently created symbol:

```sql
-- PostgreSQL: Delete duplicates, keeping the one with the largest ID (newest)
DELETE FROM symbols
WHERE id IN (
    SELECT id
    FROM (
        SELECT id,
               ROW_NUMBER() OVER (
                   PARTITION BY name, category, file_id, start_line 
                   ORDER BY id DESC
               ) as rn
        FROM symbols
    ) t
    WHERE rn > 1
);
```

### Option 3: Manual Cleanup Script

Create a Java service method to clean up duplicates:

```java
@Transactional
public void cleanupDuplicateSymbols() {
    // Find duplicates
    String sql = """
        SELECT id FROM (
            SELECT id,
                   ROW_NUMBER() OVER (
                       PARTITION BY name, category, file_id, start_line 
                       ORDER BY id
                   ) as rn
            FROM symbols
        ) t
        WHERE rn > 1
        """;
    
    List<UUID> duplicateIds = entityManager.createNativeQuery(sql, UUID.class).getResultList();
    
    log.info("Found {} duplicate symbols to delete", duplicateIds.size());
    
    // Delete duplicates
    for (UUID id : duplicateIds) {
        symbolRepository.deleteById(id);
    }
    
    log.info("Deleted {} duplicate symbols", duplicateIds.size());
}
```

## Before Running Cleanup

### 1. Backup Your Database

```bash
# PostgreSQL backup
pg_dump -h localhost -U decode_user decode_db > backup_before_cleanup.sql
```

### 2. Check Impact

```sql
-- See how many duplicates will be deleted
SELECT COUNT(*) as will_be_deleted
FROM (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY name, category, file_id, start_line 
               ORDER BY id
           ) as rn
    FROM symbols
) t
WHERE rn > 1;
```

### 3. Check Relationships

If you have relationships pointing to duplicate symbols, you may need to update them:

```sql
-- Find relationships pointing to duplicate symbols
SELECT sr.id, sr.source_symbol_id, sr.target_symbol_id
FROM symbol_relationships sr
WHERE sr.source_symbol_id IN (
    SELECT id FROM (
        SELECT id,
               ROW_NUMBER() OVER (
                   PARTITION BY name, category, file_id, start_line 
                   ORDER BY id
               ) as rn
        FROM symbols
    ) t
    WHERE rn > 1
)
OR sr.target_symbol_id IN (
    SELECT id FROM (
        SELECT id,
               ROW_NUMBER() OVER (
                   PARTITION BY name, category, file_id, start_line 
                   ORDER BY id
               ) as rn
        FROM symbols
    ) t
    WHERE rn > 1
);
```

## After Cleanup

### 1. Re-vectorize Projects

After cleaning duplicates, you may want to re-vectorize to ensure Qdrant is in sync:

```bash
# Trigger vectorization for all projects
curl -X POST "http://localhost:8080/api/vectorizer/trigger?projectId={projectId}"
```

### 2. Verify No New Duplicates

The new duplicate checking should prevent future duplicates. Verify by re-parsing a project and checking:

```sql
-- Should return 0 after re-parsing
SELECT COUNT(*) as duplicates
FROM (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY name, category, file_id, start_line 
               ORDER BY id
           ) as rn
    FROM symbols
) t
WHERE rn > 1;
```

## Prevention

The duplicate checking we added will prevent future duplicates:

- **During Parsing**: Checks if symbol exists (name + category + file + line) before saving
- **During Vectorization**: Checks if symbol exists in Qdrant before vectorizing

## Summary

1. **Check for duplicates**: Use the SQL queries above
2. **Backup database**: Always backup before cleanup
3. **Clean up duplicates**: Use Option 1 (keep oldest) or Option 2 (keep newest)
4. **Re-vectorize**: After cleanup, re-vectorize affected projects
5. **Verify**: Confirm no new duplicates are created

The duplicate checking we added will prevent this issue going forward.
