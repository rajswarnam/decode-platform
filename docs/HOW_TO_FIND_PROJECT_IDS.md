# How to Find Project IDs

## Quick Answer

Projects are identified by **UUID** (not sequential numbers). You can find project IDs in several ways:

---

## Method 1: Browser DevTools (Easiest)

1. **Open Browser DevTools** (F12 or Cmd+Option+I)
2. **Go to Network tab**
3. **Open the project selector dropdown** in the UI
4. **Find the API request**: Look for `GET /api/v1/explore/projects`
5. **Click on the response** to see the JSON
6. **Each project has an `id` field** with a UUID like: `"id": "550e8400-e29b-41d4-a716-446655440000"`

**Example Response**:
```json
[
  {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "name": "fusion-master (1)/fusion-master/Argo/Common/Group/H/Transaction",
    "domain": "fusion",
    "status": "COMPLETED",
    ...
  },
  {
    "id": "123e4567-e89b-12d3-a456-426614174000",
    "name": "ratehub-services-master",
    "domain": "General",
    ...
  }
]
```

---

## Method 2: Direct API Call

### Using curl:
```bash
curl http://localhost:8082/api/v1/explore/projects | jq '.[] | {id: .id, name: .name, domain: .domain}'
```

### Using browser:
Navigate to: `http://localhost:8082/api/v1/explore/projects`

You'll see a JSON array with all projects and their IDs.

---

## Method 3: Database Query

If you have database access:

```sql
-- List all projects with IDs
SELECT id, name, domain, status, created_at 
FROM projects 
ORDER BY created_at DESC;

-- Find project by name
SELECT id, name, domain 
FROM projects 
WHERE name LIKE '%fusion%';

-- Find project by domain
SELECT id, name, domain 
FROM projects 
WHERE domain = 'fusion';
```

**Project IDs are UUIDs** (not integers), so they look like:
```
550e8400-e29b-41d4-a716-446655440000
```

---

## Method 4: Add to UI (Future Enhancement)

Currently, the project selector UI shows project names but not IDs. To see IDs in the UI, you could:

1. **Right-click** on a project in the selector
2. **Inspect Element** in browser DevTools
3. **Find the data attribute** or React component props

Or we could enhance the UI to show IDs when you hover over a project.

---

## Method 5: Check Logs

Project IDs may appear in logs during ingestion or analysis:

```bash
# Look for project registration
grep "Registered New Project" logs/ingestion-engine.log

# Look for project references in context orchestrator
grep "project.*id" logs/context-orchestrator.log
```

---

## Understanding Project Structure

Projects have the following structure:

```typescript
interface Project {
  id: string;              // UUID (e.g., "550e8400-e29b-41d4-a716-446655440000")
  name: string;            // Human-readable name (e.g., "fusion-master (1)/fusion-master/Argo/Common/Group/H/Transaction")
  domain: string;          // Group/domain (e.g., "fusion", "General")
  status: string;          // "PENDING", "IN_PROGRESS", "COMPLETED", "FAILED"
  ingestionProgress: number; // 0-100
  techStack: string[];     // ["C/C++", "ACLF", "ASP.NET"]
  // ... other fields
}
```

---

## Common Use Cases

### 1. Find ID for a specific project by name:
```bash
curl http://localhost:8082/api/v1/explore/projects | \
  jq '.[] | select(.name | contains("Common/Group/H/Transaction")) | .id'
```

### 2. List all project IDs:
```bash
curl http://localhost:8082/api/v1/explore/projects | jq '.[].id'
```

### 3. Get project details by ID:
```sql
SELECT * FROM projects WHERE id = '550e8400-e29b-41d4-a716-446655440000';
```

---

## Quick Reference

- **API Endpoint**: `GET /api/v1/explore/projects`
- **ID Type**: UUID (36 characters)
- **Format**: `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`
- **Database Table**: `projects`
- **ID Column**: `id` (UUID type)

---

## Note

**Project names are used more commonly than IDs** in the codebase. Most APIs accept project names instead of IDs. For example:

- `/api/v1/explore/metrics?project=<project-name>` (uses name, not ID)
- `/api/v1/explore/mappings?project=<project-name>` (uses name, not ID)

So you may not need the ID unless you're doing direct database queries or specific API calls that require it.
