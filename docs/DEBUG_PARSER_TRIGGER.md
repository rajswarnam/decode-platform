# Debugging Parser Trigger Issues

## Quick Database Check

Run these SQL queries to verify projects exist:

```sql
-- Check if projects exist
SELECT id, name, base_path, created_at 
FROM projects 
ORDER BY created_at DESC 
LIMIT 10;

-- Count total projects
SELECT COUNT(*) as total_projects FROM projects;

-- Check a specific project by name
SELECT id, name, base_path 
FROM projects 
WHERE name LIKE '%fusion%' 
LIMIT 5;
```

## Verify Project ID

If you're using a project ID, make sure it exists:

```sql
-- Replace <project-id> with your actual UUID
SELECT id, name, base_path 
FROM projects 
WHERE id = '<project-id>';
```

## Check if Code-Parser Can See Projects

The code-parser service should be able to see all projects since they share the same database. However, verify:

1. **Both services use same database:**
   - `code-parser`: `jdbc:postgresql://postgres:5432/decode`
   - `ingestion-engine`: `jdbc:postgresql://postgres:5432/decode`

2. **Check code-parser logs for database connection:**
   ```bash
   docker-compose logs code-parser | grep -i "hikari\|database\|connection"
   ```

## Common Issues

### Issue 1: Project Not Found
**Symptom:** Trigger returns 404 or "Project not found"

**Solution:**
- Verify project exists in database (use SQL queries above)
- Check if project ID is correct
- Ensure project was created by ingestion-engine

### Issue 2: No Logs Appearing
**Symptom:** Trigger returns 200 but no logs in code-parser

**Possible Causes:**
1. **Thread not starting** - Check for `System.out.println` messages
2. **Logging misconfigured** - Check `application.yaml` logging levels
3. **Service not receiving request** - Verify endpoint URL is correct

**Debug Steps:**
```bash
# Check if endpoint is being called
docker-compose logs code-parser | grep -i "TRIGGER ENDPOINT"

# Check for System.out.println (bypasses logging framework)
docker-compose logs code-parser | grep -i "TRIGGER ENDPOINT CALLED"

# Check thread creation
docker-compose logs code-parser | grep -i "THREAD STARTED"

# Check parser service
docker-compose logs code-parser | grep -i "PARSER SERVICE"
```

### Issue 3: Database Schema Mismatch
**Symptom:** Projects exist but code-parser can't find them

**Solution:**
- Both services use same table (`projects`)
- Code-parser's Project entity has fewer fields, but that's OK
- JPA will ignore extra columns

## Test Endpoint Directly

```bash
# Get a project ID first
curl http://localhost:8080/api/v1/projects | jq '.[0].id'

# Use that ID to trigger parsing
curl -X POST "http://localhost:8080/api/parser/trigger?projectId=<project-id>"

# Check response - should include threadName and threadState
```

## Verify Database Connection

```bash
# Connect to postgres container
docker-compose exec postgres psql -U decode_user -d decode

# Then run SQL queries above
```
