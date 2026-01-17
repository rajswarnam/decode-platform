# Re-Ingestion Guide - Proper Data Population

## ✅ Cleanup Complete

All data has been cleared:
- ✅ Qdrant `symbols` collection deleted
- ✅ Postgres tables truncated (symbols, source_files, projects)
- ✅ MinIO bucket cleared

## 📋 Re-Ingestion Steps

### Step 1: Navigate to Ingestion UI
Open: `http://localhost:5173` → Click "Project Ingestion"

### Step 2: Upload metasfresh Project

**Option A: Git Clone (Recommended)**
1. Click "Git Clone" tab
2. Enter repository URL: `https://github.com/metasfresh/metasfresh.git`
3. Click "Clone & Ingest"
4. Wait for completion (this will take 10-15 minutes for a large repo)

**Option B: Zip Upload**
1. If you have metasfresh already downloaded as a zip
2. Click "Upload Zip" tab
3. Select the zip file
4. Click "Upload & Ingest"

### Step 3: Monitor Ingestion Progress

The ingestion process will:
1. **Extract files** → MinIO storage
2. **Parse code** → Extract symbols (classes, methods, variables)
3. **Store metadata** → Postgres (projects, source_files, symbols tables)
4. **Vectorize** → Generate embeddings and store in Qdrant

**Expected Timeline**:
- Small project (< 100 files): 2-3 minutes
- Medium project (100-1000 files): 5-10 minutes
- Large project (metasfresh ~5000 files): 15-20 minutes

### Step 4: Verify Ingestion

After completion, verify the data:

```bash
# Check Postgres
psql -h localhost -U kothuparotta -d decode -c "
SELECT 
  (SELECT COUNT(*) FROM projects) as projects,
  (SELECT COUNT(*) FROM source_files) as files,
  (SELECT COUNT(*) FROM symbols) as symbols;
"

# Check Qdrant
curl -s http://localhost:6333/collections/symbols | jq '.result.points_count'

# Check MinIO
# Should see files in the bucket via http://localhost:9001 (minioadmin/minioadmin)
```

**Expected Results**:
- Projects: 1 (metasfresh)
- Files: ~5000
- Symbols: ~40,000-50,000
- Qdrant points: Same as symbols count

### Step 5: Test BRD Generation

Once ingestion is complete:
1. Go to "Semantic Explorer"
2. Select domain: "metasfresh"
3. Enter query:
   ```
   You are a Business Analyst expert leading a team of AI agents.
   Generate a comprehensive Business Requirements Document (BRD) for this project.
   ```
4. Watch the sidebar populate with:
   - Worker assignments
   - Files being analyzed
   - Evidence quality score > 0

## 🔍 Troubleshooting

### If ingestion fails:
- Check Docker logs: `docker logs decode-workspace-ingestion-engine-1`
- Verify services are running: `docker ps`
- Check disk space: `df -h`

### If symbols count doesn't match Qdrant:
- There's a sync issue - check ingestion logs for errors
- The vectorizer service may have failed

### If BRD still shows 0 files:
- Verify domain selection matches the ingested project
- Check that symbols have `source_file_id` populated:
  ```sql
  SELECT COUNT(*) FROM symbols WHERE source_file_id IS NOT NULL;
  ```

## 🎯 Next Steps After Successful Ingestion

Once data is properly populated, we'll implement:
1. **Lexical Scout Agent** - Domain discovery phase
2. **Enhanced Architect** - Uses domain vocabulary for better planning
3. **Improved Evidence Extraction** - Better file-to-business-logic mapping
