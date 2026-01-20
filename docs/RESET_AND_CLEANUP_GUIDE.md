# Reset and Cleanup Guide

## When to Reset

You should consider resetting if:
- ✅ **Duplicates in Qdrant**: You suspect duplicate vectors were created
- ✅ **Inconsistent State**: Database and Qdrant are out of sync
- ✅ **Fresh Start**: You want to test with clean data after fixes
- ✅ **Corrupted Data**: Parsing/vectorization errors left bad data

## Reset Options

### Option 1: Full Reset (Recommended for Duplicates)

**What it does:**
- Deletes PostgreSQL database (all projects, symbols, files)
- Deletes Qdrant vectors (all embeddings)
- Deletes MinIO files (all uploaded source files)
- Restarts services with clean state

**When to use:**
- You have duplicate vectors in Qdrant
- You want a completely fresh start
- You're okay re-uploading all projects

**How to run:**
```bash
./scripts/reset-decode-platform.sh
```

**What you'll need to do after:**
1. Re-upload all project files via UI or API
2. Wait for parsing to complete
3. Wait for vectorization to complete

---

### Option 2: Qdrant Only (Faster, Preserves Database)

**What it does:**
- Deletes Qdrant vectors only
- Preserves PostgreSQL database (all symbols remain)
- Preserves MinIO files (all source files remain)
- Re-vectorizes all symbols from database

**When to use:**
- You only have duplicate vectors in Qdrant
- Database is clean and correct
- You want to keep parsed symbols
- You want faster reset (no re-parsing needed)

**How to run:**
```bash
./scripts/clean-qdrant-only.sh
```

**What you'll need to do after:**
1. Wait for re-vectorization to complete
2. Check logs to verify no duplicates

---

## Manual Cleanup (Alternative)

If you prefer manual control:

### Clean PostgreSQL Only
```bash
docker-compose down
rm -rf ./infra/postgres_data
docker-compose up -d postgres
# Wait for PostgreSQL to initialize
docker-compose up -d
```

### Clean Qdrant Only
```bash
# Option 1: Delete collection via API
curl -X DELETE http://localhost:6333/collections/symbols

# Option 2: Remove storage directory (more thorough)
docker-compose stop qdrant
rm -rf ./infra/qdrant_storage
docker-compose up -d qdrant
```

### Clean MinIO Only
```bash
docker-compose down
rm -rf ./infra/minio_data
docker-compose up -d minio
# Wait for MinIO to initialize
docker-compose up -d
```

---

## Verify No Duplicates After Reset

### Check Qdrant for Duplicates

```bash
# Count total vectors in Qdrant
curl http://localhost:6333/collections/symbols | jq '.result.points_count'

# Get all symbol_ids (should be unique)
curl -X POST http://localhost:6333/collections/symbols/points/scroll \
  -H "Content-Type: application/json" \
  -d '{"limit": 10000, "with_payload": true}' | \
  jq '.result.points[].payload.symbol_id' | sort | uniq -d

# If uniq -d returns anything, you have duplicates
```

### Check Database vs Qdrant

```bash
# Count symbols in database
docker-compose exec postgres psql -U decode_user -d decode -c "SELECT COUNT(*) FROM symbols;"

# Count vectors in Qdrant (should match)
curl http://localhost:6333/collections/symbols | jq '.result.points_count'
```

---

## After Reset Checklist

- [ ] Services are running: `docker-compose ps`
- [ ] PostgreSQL is healthy: `docker-compose exec postgres pg_isready`
- [ ] Qdrant is healthy: `curl http://localhost:6333/health`
- [ ] MinIO is accessible: `curl http://localhost:9000/minio/health/live`
- [ ] Projects uploaded (if full reset)
- [ ] Parsing completed: Check `code-parser` logs
- [ ] Vectorization completed: Check `vectorizer-service` logs
- [ ] No duplicates: Run verification commands above

---

## Troubleshooting

### "Collection not found" Error
- This is normal after cleaning Qdrant
- Collection will be recreated when vectorizer-service starts
- Check `vectorizer-service` logs for collection creation

### "No symbols found to vectorize"
- Check if parsing completed: `docker-compose logs code-parser | grep "Parsing completed"`
- Check database: `docker-compose exec postgres psql -U decode_user -d decode -c "SELECT COUNT(*) FROM symbols;"`
- If count is 0, re-upload projects

### "Connection refused" Errors
- Wait for services to fully start: `docker-compose ps`
- Check service health: `docker-compose logs <service-name>`
- Restart services: `docker-compose restart`

---

## Best Practices

1. **Before Reset**: Backup important data if needed
2. **After Reset**: Monitor logs to ensure everything works
3. **Verify**: Always check for duplicates after reset
4. **Document**: Note which projects were uploaded for reference

---

## Summary

**For duplicate cleanup:**
- ✅ **Full Reset**: If you want clean slate and can re-upload
- ✅ **Qdrant Only**: If database is good and you just need to fix vectors

**After fixes applied:**
- New vectorization uses deterministic IDs
- Duplicates are prevented automatically
- No need for future resets (unless data corruption)
