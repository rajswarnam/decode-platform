# Parser API Reference

This document provides a complete reference for the Code Parser API endpoints.

## Base URL

```
http://localhost:8080/api/v1/parser
```

## Endpoints

### 1. Trigger Parsing for Single Project

**Endpoint:** `POST /api/v1/parser/trigger`

**Parameters:**
- `projectId` (required): UUID of the project to parse

**Example:**
```bash
curl -X POST "http://localhost:8080/api/v1/parser/trigger?projectId=123e4567-e89b-12d3-a456-426614174000"
```

**Response:**
```
Parsing triggered for <project_name>
```

**Status Codes:**
- `200 OK`: Parsing triggered successfully
- `404 NOT FOUND`: Project not found

---

### 2. Trigger Parsing for All Projects in a Group

**Endpoint:** `POST /api/v1/parser/trigger/group`

**Parameters:**
- `groupName` (required): Name of the group to parse

**Group Matching Logic:**
Projects are matched if:
- Project name starts with group name (e.g., `"fusion-master (1)/..."`)
- Project name contains `/groupName/` pattern
- Base path contains the group name
- Partial substring match (for groups > 3 chars)

**Example:**
```bash
# URL-encode the group name if it contains spaces or special characters
curl -X POST "http://localhost:8080/api/v1/parser/trigger/group?groupName=fusion-master%20(1)"
```

**Response:**
```json
{
  "message": "Parsing triggered for 5 projects in group: fusion-master (1)",
  "groupName": "fusion-master (1)",
  "projectsFound": 5,
  "projectsTriggered": 5,
  "projectNames": [
    "fusion-master (1)/fusion-master/Argo/SRW/Group/H/Transaction",
    "fusion-master (1)/fusion-master/Argo/Alert_Mgr/core/Datatist",
    ...
  ]
}
```

**Status Codes:**
- `200 OK`: Parsing triggered for one or more projects
- `404 NOT FOUND`: No projects found matching the group name

**Notes:**
- Parsing runs asynchronously for each project
- Use the preview endpoint first to see which projects will be parsed
- Projects are processed in parallel

---

### 3. Preview Projects in a Group

**Endpoint:** `GET /api/v1/parser/group/projects`

**Parameters:**
- `groupName` (required): Name of the group to query

**Example:**
```bash
curl "http://localhost:8080/api/v1/parser/group/projects?groupName=fusion-master%20(1)"
```

**Response:**
```json
{
  "groupName": "fusion-master (1)",
  "projectCount": 5,
  "projects": [
    {
      "id": "123e4567-e89b-12d3-a456-426614174000",
      "name": "fusion-master (1)/fusion-master/Argo/SRW/Group/H/Transaction",
      "basePath": "/tmp/decode-upload-xxx/fusion-master/Argo/SRW/Group/H/Transaction"
    },
    {
      "id": "223e4567-e89b-12d3-a456-426614174001",
      "name": "fusion-master (1)/fusion-master/Argo/Alert_Mgr/core/Datatist",
      "basePath": "/tmp/decode-upload-xxx/fusion-master/Argo/Alert_Mgr/core/Datatist"
    },
    ...
  ]
}
```

**Status Codes:**
- `200 OK`: Success (may return empty projects array if no matches)

**Use Cases:**
- Preview which projects will be parsed before triggering
- Verify group name matches correctly
- Get project IDs for other operations

---

### 4. Get Parsing Status

**Endpoint:** `GET /api/v1/parser/status`

**Parameters:**
- `projectId` (required): UUID of the project

**Example:**
```bash
curl "http://localhost:8080/api/v1/parser/status?projectId=123e4567-e89b-12d3-a456-426614174000"
```

**Response:**
```json
{
  "projectName": "fusion-master (1)/fusion-master/Argo/SRW/Group/H/Transaction",
  "projectId": "123e4567-e89b-12d3-a456-426614174000",
  "symbolCount": 1250,
  "status": "COMPLETED"
}
```

**Status Codes:**
- `200 OK`: Status retrieved successfully
- `404 NOT FOUND`: Project not found

**Status Values:**
- `COMPLETED`: Project has symbols (parsing done)
- `PENDING`: No symbols found (not parsed yet)

---

## Common Use Cases

### Use Case 1: Parse All Projects in a Group

```bash
# 1. Preview projects
GROUP_NAME="fusion-master (1)"
curl "http://localhost:8080/api/v1/parser/group/projects?groupName=$GROUP_NAME" | jq

# 2. Trigger parsing
curl -X POST "http://localhost:8080/api/v1/parser/trigger/group?groupName=$GROUP_NAME"

# 3. Monitor logs
docker-compose logs -f code-parser
```

### Use Case 2: Parse Single Project

```bash
# 1. Get project ID
curl http://localhost:8080/api/v1/projects | jq '.[] | {id, name}'

# 2. Trigger parsing
PROJECT_ID="123e4567-e89b-12d3-a456-426614174000"
curl -X POST "http://localhost:8080/api/v1/parser/trigger?projectId=$PROJECT_ID"

# 3. Check status
curl "http://localhost:8080/api/v1/parser/status?projectId=$PROJECT_ID" | jq
```

### Use Case 3: Find Group Name from Project

If you have a project name and want to find its group:

```bash
# Get all projects and filter
curl http://localhost:8080/api/v1/projects | jq '.[] | select(.name | contains("fusion-master")) | .name'

# Extract group name (first part before first "/")
# Example: "fusion-master (1)/fusion-master/Argo/..." → group is "fusion-master (1)"
```

---

## Group Name Examples

### Example 1: Exact Group Name

**Group:** `"fusion-master (1)"`

**Matches:**
- ✅ `"fusion-master (1)/fusion-master/Argo/SRW/Group/H/Transaction"`
- ✅ `"fusion-master (1)/fusion-master/Argo/Alert_Mgr/core/Datatist"`
- ❌ `"fusion-master (2)/fusion-master/..."` (different group number)

### Example 2: Partial Match

**Group:** `"fusion-master"`

**Matches:**
- ✅ `"fusion-master (1)/fusion-master/..."`
- ✅ `"fusion-master (2)/fusion-master/..."`
- ✅ Any project with "fusion-master" in name or path

### Example 3: Path-Based Group

**Group:** `"Argo"`

**Matches:**
- ✅ `"fusion-master (1)/fusion-master/Argo/SRW/..."`
- ✅ `"fusion-master (1)/fusion-master/Argo/Alert_Mgr/..."`
- ✅ Any project with "Argo" in name or path

---

## Error Handling

### Project Not Found

**Error:**
```json
{
  "error": "Project not found. Project may need to be synced to code-parser database."
}
```

**Solution:**
- Ensure project exists in database
- Check project ID is correct
- Verify project was ingested successfully

### Group Not Found

**Error:**
```json
{
  "error": "No projects found for group: <groupName>",
  "groupName": "<groupName>",
  "projectsFound": 0
}
```

**Solution:**
- Verify group name spelling
- Check project names in database: `SELECT name FROM projects;`
- Try partial group name (e.g., "fusion" instead of "fusion-master (1)")
- Use preview endpoint to test matching

---

## Best Practices

1. **Always preview first:** Use `/group/projects` endpoint before triggering to verify matches
2. **URL-encode group names:** Use `%20` for spaces, encode special characters
3. **Monitor logs:** Watch `code-parser` logs to track parsing progress
4. **Check status:** Use `/status` endpoint to verify parsing completed
5. **Use specific group names:** More specific names reduce false matches

---

## Related Documentation

- [How to Run ACLF Parsing](./HOW_TO_RUN_ACLF_PARSING.md) - Step-by-step guide
- [ACLF Parser Hybrid Approach](./ACLF_PARSER_HYBRID_APPROACH.md) - Technical details
- [How to Verify ACLF Symbols](./HOW_TO_VERIFY_ACLF_SYMBOLS.md) - Verification guide
