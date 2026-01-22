# How the Head Architect Devises the Execution Plan

## Overview

The **Head Architect** (implemented in `AgentOrchestrator.createExecutionPlan()`) is an LLM-powered planning agent that analyzes the user's query and creates a parallel execution plan with multiple worker tasks.

## Planning Process Flow

```
User Query
    ↓
1. Intent Detection (BUSINESS/TECHNICAL/GENERAL)
    ↓
2. Project Tech Stack Detection
    ↓
3. LLM Prompt Construction (with context + guidance)
    ↓
4. LLM Generates Plan (list of worker tasks)
    ↓
5. Parse LLM Response → WorkerTask objects
    ↓
6. Return Execution Plan
```

## Step-by-Step Process

### Step 1: Intent Detection

The system first detects the query intent:

```java
Intent queryIntentType = detectIntent(userQuery);
```

**Intent Types:**
- **BUSINESS**: Questions about features, workflows, use cases, overviews, architecture
- **TECHNICAL**: Questions about bugs, errors, implementation details, code quality
- **GENERAL**: Greetings or indistinct queries

**Detection Method:**
- Uses pattern matching for common keywords
- Falls back to LLM classification if patterns don't match

### Step 2: Project Tech Stack Detection

The architect retrieves the project's tech stack to select appropriate workers:

```java
List<String> projectTechStack = getProjectTechStack(domain);
```

**Tech Stack Examples:**
- `["Java", "React", "PostgreSQL"]`
- `["C", "ACLF", "ASP.NET"]`
- `["COBOL", "Java"]`

This ensures workers match the actual technologies in the project.

### Step 3: Context Preparation

The architect receives:
- **User Query**: The original question
- **Enriched Context**: Project structure + domain map from Lexical Scout
- **Intent Guidance**: Instructions based on intent type (BUSINESS/TECHNICAL/GENERAL)
- **Tech Stack Guidance**: Which workers to use based on project tech stack

### Step 4: LLM Prompt Construction

The architect builds a comprehensive prompt with:

**1. User Goal:**
```
User Goal: "{userQuery}"
```

**2. Project Context:**
```
Project Context:
{enrichedContext}
```

**3. Intent-Specific Guidance:**

**For BUSINESS Queries:**
```
**BUSINESS QUERY DETECTED**: Focus EXCLUSIVELY on discovering business domains, use cases, and capabilities.

**YOUR MISSION**: Discover all major business domains this system supports by analyzing the codebase structure and business entities.

**HOW TO DISCOVER DOMAINS**:
1. Look for package/module names, table names, service names, class names that suggest business concepts
2. Identify business entity classes/tables (e.g., Order, Customer, Product, Invoice, Transaction)
3. Look for workflow/process names that indicate business capabilities
4. Focus on WHAT business problems the system solves, not how it's implemented

**CREATE TASKS ABOUT**:
- Discovering business domains that exist in the codebase
- Understanding business workflows and use cases
- Mapping business entities discovered in the code
```

**For TECHNICAL Queries:**
```
**TECHNICAL QUERY DETECTED**: Focus on implementation details, code quality, type safety, and technical architecture.
- Investigate technical implementations, APIs, and data structures
- Check for type mismatches, schema drift, and boundary contracts
- Analyze code quality, performance, and reliability
```

**4. Worker Selection Guidance:**

Based on tech stack, the architect receives guidance like:
```
**DYNAMIC WORKER SELECTION BASED ON PROJECT TECH STACK**:
Available Worker Personas: BACKEND_JAVA, FRONTEND_REACT, DATABASE_SQL, LEGACY_COBOL, 
LOGIC_EXTRACTOR, BACKEND_C, BACKEND_ASPNET, CONFIG_ACLF, FRONTEND_HTML

- **MUST INCLUDE**: BACKEND_C (for C/C++ source files: .c, .cpp, .h)
- **MUST INCLUDE**: BACKEND_ASPNET (for ASP.NET files: .aspx, .aspx.cs, .aspx.vb)
- **MUST INCLUDE**: CONFIG_ACLF (for ACLF configuration files: .aclf)
- **ALWAYS INCLUDE**: DATABASE_SQL (for database schema and data models)
- **ALWAYS INCLUDE**: LOGIC_EXTRACTOR (for business logic and variable mapping)
```

**5. Example Tasks:**

The architect receives examples of correct task formats:

```
Example tasks for BUSINESS queries:
LOGIC_EXTRACTOR|primary-domain|What is the primary business domain this system addresses?
LOGIC_EXTRACTOR|business-modules|What distinct business modules exist in this system?
DATABASE_SQL|business-entities|What are the main business entities and their relationships?

Example tasks for TECHNICAL queries:
BACKEND_JAVA|procurement-backend|Analyze the logic for Purchase Order creation.
FRONTEND_REACT|frontend-webui|Identify how users trigger the order process.
DATABASE_SQL|schema-design|Review database schema and relationships.
```

**6. Task Format Instructions:**

```
Format: ONE LINE per task.
Format pattern: PERSONA|FOCUS_AREA|SPECIFIC_QUESTION
```

### Step 5: LLM Generation

The architect calls the LLM with the constructed prompt:

```java
String response = blockingCall(prompt);
```

The LLM returns a list of tasks in the format:
```
1. LOGIC_EXTRACTOR|primary-domain|What is the primary business domain?
2. DATABASE_SQL|business-entities|What are the main business entities?
3. BACKEND_JAVA|order-processing|How does order processing work?
```

### Step 6: Plan Parsing

The architect parses the LLM response:

```java
List<WorkerTask> tasks = new ArrayList<>();
for (String line : response.split("\n")) {
    // Strip leading numbers (e.g., "1. BACKEND_JAVA|..." -> "BACKEND_JAVA|...")
    String cleanedLine = line.replaceFirst("^\\d+\\.\\s*", "").trim();
    
    if (cleanedLine.contains("|")) {
        String[] parts = cleanedLine.split("\\|", 3);
        if (parts.length == 3) {
            tasks.add(WorkerTask.builder()
                .persona(WorkerPersona.valueOf(parts[0].trim()))
                .focusArea(parts[1].trim())
                .specificQuestion(parts[2].trim())
                .status("PENDING")
                .build());
        }
    }
}
```

**Task Structure:**
- **Persona**: Which worker type (e.g., `BACKEND_JAVA`, `LOGIC_EXTRACTOR`)
- **Focus Area**: What area to analyze (e.g., `order-processing`, `business-entities`)
- **Specific Question**: The question for the worker to answer

## Key Design Principles

### 1. Intent-Aware Planning

The architect adapts its strategy based on query intent:
- **BUSINESS queries**: Focus on discovering domains, use cases, workflows
- **TECHNICAL queries**: Focus on implementation, code quality, architecture
- **GENERAL queries**: Balance both approaches

### 2. Dynamic Worker Selection

Workers are selected based on:
- **Project tech stack**: Only include workers for technologies actually in the project
- **Query intent**: Prioritize certain workers (e.g., `LOGIC_EXTRACTOR` for business queries)
- **Task requirements**: Match worker capabilities to task needs

### 3. Parallel Execution

The plan creates multiple tasks that can run in parallel:
- Each task is assigned to a different worker persona
- Workers operate independently on different focus areas
- Results are synthesized later

### 4. Context-Aware

The architect uses:
- **Project structure**: From Lexical Scout domain discovery
- **Domain vocabulary**: Business terms and entities discovered
- **Tech stack**: Actual technologies in the project

## Example Planning Scenarios

### Scenario 1: Business Query

**User Query:** "What business domains does this system support?"

**Intent:** BUSINESS

**Tech Stack:** `["Java", "React", "PostgreSQL"]`

**Generated Plan:**
```
1. LOGIC_EXTRACTOR|primary-domain|What is the primary business domain this system addresses?
2. LOGIC_EXTRACTOR|business-modules|What distinct business modules exist in this system?
3. DATABASE_SQL|business-entities|What are the main business entities and their relationships?
4. BACKEND_JAVA|business-workflows|What key business workflows does this system support?
5. FRONTEND_REACT|user-capabilities|What business capabilities does the UI provide?
```

### Scenario 2: Technical Query

**User Query:** "How does the order processing work?"

**Intent:** TECHNICAL

**Tech Stack:** `["Java", "PostgreSQL"]`

**Generated Plan:**
```
1. BACKEND_JAVA|order-processing|Analyze the logic for order processing and creation.
2. DATABASE_SQL|order-schema|Review database schema for order-related tables.
3. LOGIC_EXTRACTOR|order-workflow|Map the order processing workflow and state transitions.
```

### Scenario 3: Multi-Tech Stack Query

**User Query:** "What are all the transactions in the ACLF files?"

**Intent:** TECHNICAL

**Tech Stack:** `["C", "ACLF", "ASP.NET"]`

**Generated Plan:**
```
1. CONFIG_ACLF|transactions|Extract all Transaction definitions from ACLF files.
2. BACKEND_C|transaction-handlers|Find C code that handles these transactions.
3. BACKEND_ASPNET|transaction-ui|Identify ASP.NET pages that trigger transactions.
```

## Validation and Quality Checks

The architect includes validation instructions in the prompt:

```
**VALIDATION**: Before creating a task, ask yourself: 
"Does this task help discover WHAT business domains the system supports, 
or does it analyze HOW the system is implemented technically?" 
Only create the former type of tasks.
```

This ensures tasks align with the query intent.

## Plan Execution

Once the plan is created:

1. **Task Dispatch**: Each task is assigned to its worker persona
2. **Parallel Execution**: Workers execute tasks concurrently
3. **Context Retrieval**: Each worker retrieves relevant code context
4. **Analysis**: Workers analyze their assigned focus area
5. **Report Generation**: Workers generate reports
6. **Synthesis**: Reports are synthesized into final answer

## Summary

The Head Architect uses **LLM-powered planning** with:

- ✅ **Intent-aware guidance** (BUSINESS vs TECHNICAL)
- ✅ **Dynamic worker selection** (based on tech stack)
- ✅ **Context-rich prompts** (project structure + domain map)
- ✅ **Structured output** (PERSONA|FOCUS_AREA|QUESTION format)
- ✅ **Validation rules** (ensure tasks match intent)

This creates a **parallel execution plan** that efficiently distributes work across multiple specialized workers, each focusing on a specific aspect of the user's query.
