# Dictionary Service Mapping Explanation

## Overview

`DictionaryService` maps **technical code symbols** (from parsed code) to **business-friendly names** and stores these mappings in the `global_dictionary` table.

## What is Being Mapped

### Input: Technical Symbols from Parsed Code

**Source**: Symbols extracted from code by `code-parser` service

**Examples**:
- `RequestContext` (class name)
- `identifier` (field name)
- `correlationID` (field name)
- `maxSearchResultPageSize` (field name)
- `getIdentifier()` (method name)
- `setCorrelationID()` (method name)

**Stored in**: `symbols` table
- `name`: Technical name (e.g., `RequestContext`)
- `category`: Type (e.g., `class`, `method`, `field`)
- `analysis_status`: `PENDING` → `COMPLETED` or `FAILED`

### Output: Business-Friendly Mappings

**Target**: Human-readable business names and labels

**Examples**:
- `RequestContext` → `Request Context` (Standard Label)
- `identifier` → `Identifier` (Standard Label)
- `correlationID` → `Correlation ID` (Standard Label)
- `maxSearchResultPageSize` → `Maximum Search Result Page Size` (Standard Label)
- `getIdentifier` → `Get Identifier` (Standard Label)

## Where Mappings Are Stored

### Database Table: `global_dictionary`

**Schema**:
```sql
CREATE TABLE public.global_dictionary (
    id bigint PRIMARY KEY,
    technical_name text NOT NULL,          -- Original symbol name (e.g., "RequestContext")
    business_name text,                    -- Business name in camelCase (e.g., "requestContext")
    standard_label text,                   -- Human-readable label (e.g., "Request Context")
    domain text,                           -- Project domain/group (e.g., "ratehub")
    description text,                      -- Business description (e.g., "Represents request context")
    confidence_score double precision,     -- LLM confidence (e.g., 0.95)
    created_by_agent boolean DEFAULT true  -- True if created by LLM/agent
);

-- Unique constraint: (technical_name, domain)
-- Prevents duplicate mappings for same symbol in same domain
```

**Example Rows**:
```sql
-- Row 1
technical_name: "RequestContext"
business_name: "requestContext"
standard_label: "Request Context"
domain: "ratehub"
description: "Represents the context of a request"
confidence_score: 0.95
created_by_agent: true

-- Row 2
technical_name: "correlationID"
business_name: "correlationId"
standard_label: "Correlation ID"
domain: "ratehub"
description: "Unique identifier for correlating requests"
confidence_score: 0.95
created_by_agent: true
```

## Mapping Process

### Step 1: Symbol Extraction (by code-parser)

```
Java Code:
public class RequestContext {
    private String correlationID;
    // ...
}

↓ Parsed by code-parser

Symbols Created:
- Symbol(name="RequestContext", category="class", analysis_status="PENDING")
- Symbol(name="correlationID", category="field", analysis_status="PENDING")
```

### Step 2: Dictionary Population (by DictionaryService)

```
For each PENDING symbol:

1. Build Prompt:
   "Act as a Business Analyst specializing in the 'ratehub' domain.
    Analyze the technical symbol 'RequestContext' (Category: class).
    Provide:
    1. A Human-Readable Business Name (camelCase).
    2. A Standard Label (Title Case).
    3. A Business Description (1 sentence).
    
    Format: BusinessName|StandardLabel|Description"

2. Call LLM via Gateway:
   Input: Prompt
   Output: "requestContext|Request Context|Represents the context of a request"

3. Parse Response:
   - parts[0] = "requestContext" (business name)
   - parts[1] = "Request Context" (standard label)
   - parts[2] = "Represents the context of a request" (description)

4. Save to global_dictionary:
   - technical_name: "RequestContext"
   - business_name: "requestContext"
   - standard_label: "Request Context"
   - domain: "ratehub"
   - description: "Represents the context of a request"

5. Update Symbol Status:
   - analysis_status: "PENDING" → "COMPLETED"
```

### Step 3: Storage

**Table**: `global_dictionary`

**Key Fields**:
- `technical_name`: Original code symbol name
- `business_name`: Business-friendly camelCase name
- `standard_label`: Human-readable title case label (this is what you see in logs)
- `domain`: Project domain/group (for multi-project scenarios)
- `description`: Business description of what the symbol represents

**Uniqueness**: 
- Unique constraint on `(technical_name, domain)`
- Same technical name can have different mappings in different domains
- Example: `RequestContext` in "ratehub" domain vs "payment" domain

## Logs You See

### Example Log Output

```
INFO: Mapped: RequestContext -> Request Context
INFO: Mapped: identifier -> Identifier
INFO: Mapped: correlationID -> Correlation ID
INFO: Mapped: maxSearchResultPageSize -> Maximum Search Result Page Size
INFO: Mapped: getIdentifier -> Get Identifier
```

**What This Means**:
- `RequestContext` is the **technical name** (from code)
- `Request Context` is the **standard label** (business-friendly name)
- Mapped = Created entry in `global_dictionary` table
- Symbol status updated to `COMPLETED`

## How Mappings Are Used

### Current Usage

1. **Business Name Mapping**: Translate technical code symbols to business-friendly names
2. **Analysis Reports**: Use business names in generated reports and documentation
3. **User Interface**: Display business-friendly names instead of technical names
4. **Cross-Project Consistency**: Ensure consistent naming across projects in same domain

### Future Usage (Potential)

1. **Semantic Search**: Use business names for better search results
2. **Documentation Generation**: Generate documentation with business-friendly terminology
3. **Code Analysis**: Analyze code using business terminology
4. **Report Generation**: Generate reports using business names instead of technical names

## Mapping Structure

### LLM Response Format

**Prompt**:
```
Analyze the technical symbol 'RequestContext' (Category: class).
Provide:
1. A Human-Readable Business Name (camelCase).
2. A Standard Label (Title Case).
3. A Business Description (1 sentence).

Format: BusinessName|StandardLabel|Description
```

**Example Response**:
```
requestContext|Request Context|Represents the context of a request
```

**Parsed**:
- `parts[0]` → `business_name`: "requestContext"
- `parts[1]` → `standard_label`: "Request Context" (this is what appears in logs)
- `parts[2]` → `description`: "Represents the context of a request"

## Database Storage

### Example Database Entries

```sql
-- View mappings
SELECT technical_name, standard_label, domain, description 
FROM global_dictionary 
WHERE domain = 'ratehub'
ORDER BY technical_name;

-- Results:
technical_name          | standard_label                     | domain  | description
------------------------|-----------------------------------|---------|----------------------------------------
RequestContext          | Request Context                    | ratehub | Represents the context of a request
correlationID           | Correlation ID                     | ratehub | Unique identifier for correlating requests
getIdentifier           | Get Identifier                     | ratehub | Retrieves the identifier value
setCorrelationID        | Set Correlation ID                 | ratehub | Sets the correlation identifier
maxSearchResultPageSize | Maximum Search Result Page Size    | ratehub | Maximum number of results per page
```

### Unique Constraint

```sql
-- Unique constraint prevents duplicates
UNIQUE (technical_name, domain)
```

**Implications**:
- Same technical name can exist in different domains with different mappings
- Example: `RequestContext` in "ratehub" domain vs "payment" domain
- Prevents duplicate mappings within same domain

## Querying Mappings

### Find Mapping for Technical Name

```sql
-- Get business name for technical symbol
SELECT business_name, standard_label, description
FROM global_dictionary
WHERE technical_name = 'RequestContext'
  AND domain = 'ratehub';
```

### Find All Mappings for Domain

```sql
-- Get all mappings for a project domain
SELECT technical_name, standard_label, description
FROM global_dictionary
WHERE domain = 'ratehub'
ORDER BY technical_name;
```

### Find Technical Names for Business Term

```sql
-- Reverse lookup: Find technical symbols for a business term
SELECT technical_name, domain
FROM global_dictionary
WHERE standard_label ILIKE '%Request%'
  OR description ILIKE '%request%';
```

## Summary

### What DictionaryService Maps

**From** (Input):
- Technical code symbols: `RequestContext`, `correlationID`, `getIdentifier()`
- From `symbols` table (parsed by code-parser)

**To** (Output):
- Business-friendly names: `Request Context`, `Correlation ID`, `Get Identifier`
- Stored in `global_dictionary` table

### Storage Location

**Database**: PostgreSQL  
**Table**: `global_dictionary`  
**Key Columns**:
- `technical_name`: Original symbol name
- `standard_label`: Business-friendly label (shown in logs)
- `business_name`: camelCase business name
- `domain`: Project domain/group
- `description`: Business description

### Process

1. Code parsed → Symbols created (`symbols` table, `analysis_status = 'PENDING'`)
2. DictionaryService processes PENDING symbols
3. LLM generates business names via gateway
4. Mappings saved to `global_dictionary` table
5. Symbol status updated to `COMPLETED`

### Purpose

- Translate technical code terminology to business-friendly terminology
- Enable better understanding of code for non-technical stakeholders
- Support documentation and report generation
- Improve code analysis and semantic search
