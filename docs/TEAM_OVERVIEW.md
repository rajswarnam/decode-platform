# Decode.AI Platform - Team Overview Document

## Executive Summary

**Decode.AI** is an intelligent code analysis platform that transforms legacy and modern codebases into queryable knowledge graphs. It uses AI agents to automatically discover business logic, extract domain vocabulary, and generate comprehensive documentation from source code.

**Key Value Proposition:**
- **Reverse Engineering**: Understand complex, undocumented codebases in minutes
- **Multi-Language Support**: C, COBOL, Java, ASP.NET, Angular, TypeScript, ACLF
- **Business Intelligence**: Extract business entities, workflows, and domain patterns
- **Quality Assurance**: Automated QA checks for code quality, SRE risks, and evidence validation

---

## System Architecture

### Microservices Architecture

The platform consists of **8 core services**:

| Service | Purpose | Technology |
|---------|---------|------------|
| **ingestion-engine** | Project discovery, file upload, Git clone | Spring Boot, MinIO |
| **code-parser** | AST parsing, symbol extraction | Tree-sitter, Java |
| **vectorizer-service** | Embedding generation, vector storage | ONNX Runtime, Qdrant |
| **context-orchestrator** | Agent orchestration, semantic analysis | Spring AI, GPT-4o |
| **llm-gateway-service** | Rate limiting, token governance | Spring Boot, Azure AD |
| **web-frontend** | React UI, real-time progress | React, TypeScript, SSE |
| **api-gateway** | API routing, authentication | Spring Boot |
| **postgres** | Metadata storage, symbol database | PostgreSQL |

### Data Flow

```
1. User Uploads Project (ZIP/Git)
   ↓
2. Ingestion Engine → Discovers projects, filters files
   ↓
3. Code Parser → Extracts symbols (AST parsing)
   ↓
4. Vectorizer Service → Generates embeddings, stores in Qdrant
   ↓
5. Context Orchestrator → Agent swarm analyzes code
   ↓
6. Web Frontend → Displays results, real-time progress
```

---

## AI Agents (5 Specialized Agents)

### 1. 🔍 Lexical Scout Agent
**Role**: Domain Discovery & Vocabulary Extraction  
**When**: Runs first, before all other agents  
**What It Does**:
- Samples 500 documents from vector store
- Extracts business nouns (Order, Invoice, Product, Customer)
- Clusters code by modules (procurement, sales, inventory)
- Identifies domain patterns (Order-to-Cash, Procure-to-Pay)
- Generates domain vocabulary map

**Output**: Domain Map with:
- Top business entities (frequency, category, relationships)
- Module clusters (module name, file count, primary entities)
- Domain patterns (business workflows)
- Domain summary (natural language description)

**Caching**: Results cached for 1 hour per project (saves 2-3 seconds)

---

### 2. 🧠 Head Architect Agent
**Role**: Planning & Task Orchestration  
**When**: After Lexical Scout, before workers  
**What It Does**:
- Analyzes user query intent (BUSINESS vs TECHNICAL)
- Creates execution plan with 3-5 parallel worker tasks
- Assigns worker personas based on query type
- Defines focus areas (e.g., "procurement-backend", "frontend-webui")
- Monitors iteration progress and refines tasks

**Query Intent Detection**:
- **BUSINESS**: Features, workflows, "How does it work", overviews
- **TECHNICAL**: Bugs, errors, performance, SRE, refactoring

**Output**: Execution plan with worker tasks

---

### 3. 👷 Worker Agents (5 Personas)
**Role**: Parallel code analysis  
**When**: During each iteration (1-4 iterations)  
**Personas**:

| Persona | Specialization | Focus Areas |
|---------|---------------|-------------|
| **BACKEND_JAVA** | Java backend services | Service classes, controllers, repositories |
| **FRONTEND_REACT** | React/Angular frontend | Components, pages, state management |
| **DATABASE_SQL** | Database schema | Tables, relationships, queries |
| **LOGIC_EXTRACTOR** | Business logic discovery | Cross-cutting concerns, workflows |
| **LEGACY_COBOL** | COBOL mainframe code | Programs, copybooks, data divisions |
| **BACKEND_C** | C/C++ backend code | Source files, headers, data structures |
| **BACKEND_ASPNET** | ASP.NET Web Forms | .aspx, code-behind, configurations |
| **CONFIG_ACLF** | ACLF configuration | Data definitions, transaction flows, forms |
| **FRONTEND_HTML** | HTML/Web content | HTML structure, CSS, JavaScript |
| **LEGACY_COBOL** | COBOL/mainframe code | Data division, procedures |

**What Workers Do**:
1. Receive task with focus area (e.g., "procurement-backend")
2. Perform vector search using domain vocabulary
3. Retrieve relevant files from MinIO
4. Analyze code and extract business logic
5. Cite evidence: `**Evidence**: \`OrderService.java\` (lines 45-67)`
6. Generate analysis report

**Validation**: Reports must contain evidence citations or marked as `FAILED_VALIDATION`

---

### 4. 🔍 QA Agent
**Role**: Quality Assurance & Coverage Validation  
**When**: After each iteration (1-4 iterations)  
**What It Checks**:

| Check | Symbol | Description |
|-------|--------|-------------|
| **Evidence Citations** | ✅/❌ | Are all reports properly cited? |
| **Schema Drift** | ❌ | Are there type mismatches? |
| **Boundary Contracts** | ⚠️ | Are API contracts validated? |
| **SRE Risks** | ❌ | Synchronous dependencies, timeouts? |
| **Type Mismatches** | ✅/❌ | Backend vs Frontend alignment? |
| **Coverage** | ⚠️ | Are enough files analyzed? |

**Output**: QA Report with:
- Passed checks (✅)
- Failed checks (❌)
- Warnings (⚠️)
- Recommendations for refinement

**Refinement Trigger**: If QA finds ❌ or ⚠️, Architect refines tasks for next iteration

---

### 5. 📝 Business Analyst Agent
**Role**: Final Synthesis & Documentation  
**When**: After all iterations complete  
**What It Does**:
- Synthesizes all worker reports into final document
- Extracts business facts from code evidence
- Maps stakeholders from modules
- Translates technical risks to business impact
- Generates BRD (Business Requirements Document) or SRE Blueprint

**Modes**:
- **BRD Mode**: If query contains "BRD" or "Business Requirements"
  - Business Analyst persona
  - Non-technical language
  - Executive summary, stakeholders, risks, metrics
- **SRE Mode**: Technical analysis
  - SRE Architect persona
  - Infrastructure focus
  - Performance, reliability, technical debt

**Output**: Final markdown document with evidence appendix

---

## Abstract Syntax Tree (AST)

### What is AST?

**Abstract Syntax Tree (AST)** is a tree representation of source code structure. Each node represents a code construct (class, method, variable, etc.).

**Example**:
```java
public class OrderService {
    public Order createOrder(OrderRequest req) {
        return orderRepository.save(req);
    }
}
```

**AST Structure**:
```
ClassDeclaration (OrderService)
  └── MethodDeclaration (createOrder)
      ├── Parameter (req: OrderRequest)
      └── ReturnStatement
          └── MethodCall (orderRepository.save)
```

### How We Use AST

**Technology**: Tree-sitter (incremental parsing library)

**Process**:
1. **Code Parser** receives source file from MinIO
2. **Tree-sitter** parses file into AST (language-specific grammar)
3. **Traverse AST** to extract symbols:
   - Classes, methods, functions, variables
   - Relationships (method calls, field access)
   - Metadata (line numbers, file paths)
4. **Store symbols** in PostgreSQL `symbols` table
5. **Generate embeddings** for semantic search

**Supported Languages**:
- **Java**: `tree-sitter-java`
- **C**: `tree-sitter-c`
- **TypeScript/JavaScript**: `tree-sitter-javascript`
- **COBOL**: Regex-based (PoC)

**Benefits**:
- Accurate symbol extraction (not regex-based)
- Preserves code structure
- Enables semantic relationships
- Language-agnostic storage (normalized `symbols` table)

---

## Domain Extraction (Lexical Scout)

### What is Domain Extraction?

**Domain Extraction** identifies business vocabulary and domain patterns from code without prior knowledge of the codebase.

### How It Works

**Phase 0: Lexical Scout Discovery**

1. **Sample Vector Store** (500 documents)
   - Retrieves diverse code symbols from Qdrant
   - Filters by project domain if specified

2. **Extract Nouns** from symbol names
   - `OrderService` → "Order"
   - `InvoiceRepository` → "Invoice"
   - `CustomerController` → "Customer"
   - Frequency counting

3. **Identify Business Entities**
   - High-frequency nouns (Order, Invoice, Product)
   - Categorize: "Aggregate Root", "Value Object", "Service"
   - Find relationships (Order → OrderLine)

4. **Cluster by Modules**
   - Group files by path patterns (`/procurement/`, `/sales/`)
   - Identify module purpose from entity clusters
   - Count files per module

5. **Identify Domain Patterns**
   - Order-to-Cash (Order → Payment → Invoice)
   - Procure-to-Pay (Purchase → Receipt → Payment)
   - Business workflows inferred from entity relationships

6. **Generate Domain Summary**
   - Natural language description
   - Top entities, modules, patterns

### Example Output

```json
{
  "topEntities": [
    {"name": "Order", "frequency": 45, "category": "Aggregate Root"},
    {"name": "Invoice", "frequency": 32, "category": "Aggregate Root"},
    {"name": "Product", "frequency": 28, "category": "Value Object"}
  ],
  "modules": [
    {"moduleName": "procurement", "fileCount": 120, "primaryEntities": ["Order", "Purchase"]},
    {"moduleName": "sales", "fileCount": 95, "primaryEntities": ["Invoice", "Customer"]}
  ],
  "domainPatterns": ["Order-to-Cash", "Procure-to-Pay"],
  "domainSummary": "E-commerce platform with procurement and sales modules..."
}
```

### Benefits

- **Blind Discovery**: No prior knowledge needed
- **Enriches Context**: Workers use domain vocabulary for better search
- **Module Awareness**: Focuses analysis on relevant modules
- **Cached**: Results cached for 1 hour (performance optimization)

---

## Quality Checking (QA Agent)

### QA Process

**When**: After each iteration (1-4 iterations)

**Checks Performed**:

#### 1. Evidence Citations ✅/❌
- **Check**: Do all worker reports contain `**Evidence**: \`filename\``?
- **Fail**: If any report lacks evidence citations
- **Impact**: Prevents hallucination, ensures traceability

#### 2. Schema Drift Detection ❌
- **Check**: Are there type mismatches between layers?
- **Example**: Backend returns `Integer`, Frontend expects `JSON`
- **Impact**: Identifies integration risks

#### 3. Boundary Contracts ⚠️
- **Check**: Are API contracts validated?
- **Example**: Missing timeout configuration, no error handling
- **Impact**: Identifies reliability risks

#### 4. SRE Risks ❌
- **Check**: Synchronous dependencies, blocking calls?
- **Example**: `OrderService` calls `PaymentGateway` synchronously
- **Impact**: Identifies performance bottlenecks

#### 5. Type Mismatches ✅/❌
- **Check**: Backend vs Frontend alignment?
- **Example**: TypeScript interface doesn't match Java DTO
- **Impact**: Identifies integration issues

#### 6. Coverage Validation ⚠️
- **Check**: Are enough files analyzed?
- **Metrics**:
  - Files analyzed vs recommended (based on query intent)
  - Module coverage (modules analyzed / total modules)
  - Repository coverage (files analyzed / total files)
- **Impact**: Ensures comprehensive analysis

### QA Report Format

```
🕵🏻 QA Agent: Auditing Quality, Coverage & Completeness...

✅ Evidence Citations: All reports properly cited
❌ Schema Drift: Type mismatch detected in OrderService
⚠️ Boundary Contracts: Missing timeout in PaymentGateway
❌ SRE Risks: Synchronous payment call detected
✅ Type Mismatches: Frontend/Backend aligned
⚠️ Coverage: Only 15/50 recommended files analyzed (30%)
```

### Refinement Logic

**If QA finds issues**:
1. Architect reviews QA report
2. Identifies workers that need refinement
3. Generates deeper questions for those workers
4. Re-executes workers in next iteration
5. Max 4 iterations

**Early Termination**:
- If all workers found 0 files → Stop immediately (saves 75% LLM calls)
- If QA finds no issues → Proceed to synthesis

---

## Evidence Score Calculation

### What is Evidence Score?

**Evidence Quality Score** (0-10) measures the quality and quantity of evidence gathered during analysis.

### Calculation Formula

```
Evidence Score = File Coverage Score + Task Evidence Score - Iteration Penalty
```

**Capped at 0-10 range**

### Components

#### 1. File Coverage Score (0-5 points)

Based on files analyzed vs recommended:

| Files Analyzed | Score | Condition |
|----------------|-------|-----------|
| 5.0 | ≥ Recommended (50 files) | Meets recommendation |
| 4.0 | ≥ 70% of recommendation (35 files) | Good coverage |
| 3.0 | ≥ 50% of recommendation (25 files) | Moderate coverage |
| 2.0 | ≥ 30% of recommendation (15 files) | Low coverage |
| 1.0 | < 30% of recommendation (<15 files) | Very low coverage |

**Recommended Files**:
- **Comprehensive Mode**: 50 files (broad analysis)
- **Focused Mode**: 25 files (targeted analysis)
- Based on query intent and repository size

#### 2. Task Evidence Score (0-3 points)

Based on percentage of tasks with evidence sections:

```
Task Evidence Score = (Tasks with Evidence / Total Tasks) × 3.0
```

**Example**:
- 9/10 tasks have evidence → 9/10 × 3.0 = 2.7 points
- 5/10 tasks have evidence → 5/10 × 3.0 = 1.5 points

#### 3. Iteration Penalty (0-2 points deduction)

Penalty for early iterations (less comprehensive):

| Iteration | Penalty | Reason |
|-----------|---------|--------|
| 1 | -2.0 points | Early stage, limited evidence |
| 2 | -1.0 point | Still building evidence |
| 3+ | 0 points | Sufficient iterations |

### Example Calculation

**Scenario**:
- Files analyzed: 11
- Recommended: 50
- Tasks with evidence: 9/10
- Iteration: 1

**Calculation**:
1. File Coverage: 1.0 (11 < 15, <30% of 50)
2. Task Evidence: 2.7 (9/10 × 3.0)
3. Iteration Penalty: -2.0 (iteration 1)
4. **Final Score**: 1.0 + 2.7 - 2.0 = **1.7/10**

**Interpretation**: Low evidence quality (early iteration, few files analyzed)

### Another Example

**Scenario**:
- Files analyzed: 45
- Recommended: 50
- Tasks with evidence: 10/10
- Iteration: 3

**Calculation**:
1. File Coverage: 4.0 (45 ≥ 35, 70% of 50)
2. Task Evidence: 3.0 (10/10 × 3.0)
3. Iteration Penalty: 0.0 (iteration 3+)
4. **Final Score**: 4.0 + 3.0 - 0.0 = **7.0/10**

**Interpretation**: Good evidence quality (comprehensive analysis)

### Score Interpretation

| Score Range | Quality | Meaning |
|------------|---------|---------|
| 8-10 | Excellent | Comprehensive analysis, all tasks have evidence |
| 6-7.9 | Good | Good coverage, most tasks have evidence |
| 4-5.9 | Moderate | Adequate coverage, some gaps |
| 2-3.9 | Low | Limited evidence, early stage |
| 0-1.9 | Very Low | Minimal evidence, needs more iterations |

---

## Complete Analysis Flow

### Step-by-Step Process

```
1. USER QUERY
   "How does the order processing workflow work?"
   ↓

2. LEXICAL SCOUT (Phase 0)
   - Sample 500 vectors
   - Extract: Order, Invoice, Payment entities
   - Identify: procurement, sales modules
   - Cache domain map (1 hour TTL)
   ↓

3. HEAD ARCHITECT (Phase 1)
   - Analyze query intent: BUSINESS
   - Create plan: 5 worker tasks
   - Assign personas: BACKEND_JAVA, FRONTEND_REACT, DATABASE_SQL, etc.
   ↓

4. ITERATION 1: Initial Scan
   - Worker 1 (BACKEND_JAVA): Search "Order processing workflow"
     → Find OrderService.java
     → Analyze code
     → Cite evidence
   - Worker 2 (FRONTEND_REACT): Search "Order UI components"
     → Find OrderForm.tsx
     → Analyze code
     → Cite evidence
   - Worker 3-5: Similar process
   ↓

5. QA AGENT (After Iteration 1)
   - Check evidence citations: ✅ All present
   - Check coverage: ⚠️ Only 15/50 files
   - Check SRE risks: ❌ Synchronous payment call
   - Generate QA report
   ↓

6. ARCHITECT REFINEMENT
   - Review QA report
   - Identify workers needing deeper analysis
   - Generate refined questions
   - Update tasks
   ↓

7. ITERATION 2: Deep Dive
   - Re-execute workers with refined questions
   - Expand search (TopK increased)
   - Find additional files
   - Cite more evidence
   ↓

8. QA AGENT (After Iteration 2)
   - Check evidence: ✅ Improved
   - Check coverage: ✅ 35/50 files (70%)
   - Check SRE risks: ⚠️ Still present (documented)
   - Generate QA report
   ↓

9. ARCHITECT DECISION
   - QA approved → Proceed to synthesis
   - OR: Continue to iteration 3 if needed
   ↓

10. BUSINESS ANALYST (Final Synthesis)
    - Aggregate all worker reports
    - Extract business facts
    - Map stakeholders
    - Translate risks
    - Generate BRD document
    ↓

11. EVIDENCE SCORE CALCULATION
    - Files: 35/50 → 4.0 points
    - Tasks: 10/10 → 3.0 points
    - Iteration: 2 → -1.0 penalty
    - Final: 4.0 + 3.0 - 1.0 = 6.0/10
    ↓

12. RETURN TO USER
    - Final BRD document
    - Evidence appendix
    - Evidence quality score: 6.0/10
```

---

## Technology Stack

### Backend
- **Spring Boot 3.x**: Microservices framework
- **Spring AI 1.0.0-M6**: LLM integration
- **PostgreSQL**: Metadata storage
- **Qdrant**: Vector database
- **MinIO**: Object storage (S3-compatible)
- **Tree-sitter**: AST parsing
- **ONNX Runtime**: Local embeddings

### Frontend
- **React 18**: UI framework
- **TypeScript**: Type safety
- **Vite**: Build tool
- **Server-Sent Events (SSE)**: Real-time progress

### AI/ML
- **GPT-4o**: LLM (via internal gateway)
- **ONNX Models**: Local embeddings (all-MiniLM-L6-v2)
- **Azure AD**: Authentication

### Infrastructure
- **Docker Compose**: Local development
- **Kubernetes**: Production deployment
- **Jenkins**: CI/CD

---

## Key Metrics & Performance

### Analysis Performance

| Phase | Time | LLM Calls | Cacheable |
|-------|------|-----------|-----------|
| Lexical Scout | 2-3s | 0 | ✅ Yes (1hr TTL) |
| Architect Planning | 3-5s | 1 | ❌ No |
| Worker Execution (5 workers) | 10-15s | 5 | ❌ No |
| QA Audit | 5-7s | 1 | ❌ No |
| Refinement (if needed) | 3-5s | 1 | ❌ No |
| Worker Re-execution | 10-15s | 3-5 | ❌ No |
| Final Synthesis | 8-10s | 1 | ❌ No |
| **Total (with refinement)** | **45-60s** | **14-19** | - |
| **Total (early termination)** | **20-30s** | **7** | - |

### Rate Limiting

- **TPM Limit**: 250,000 tokens/minute
- **RPM Limit**: 3,000 requests/minute
- **Pause Threshold**: 220,000 tokens (88% - proactive pause)
- **Retry Logic**: Exponential backoff for 429 errors

### Optimization Impact

- **Scout Caching**: Saves 2-3s on subsequent queries
- **Early Termination**: Saves 75% LLM calls when no code found
- **Smart Refinement**: Only refines workers that found code
- **Result**: 30-70% reduction in time and cost

---

## File Ingestion & Exclusion

### Supported File Types

**Automatically Detected**:
- C/C++: `.c`, `.cpp`, `.h`, `.hpp`
- Java: `.java`
- ASP.NET: `.aspx`, `.aspx.cs`, `.aspx.vb`, `.cs`, `.vb`
- Angular/TypeScript: `.ts`, `.tsx`, `.js`, `.jsx`
- COBOL: `.cbl`, `.cob`, `.cpy`
- ACLF: `.aclf`
- Configuration: `.xml`, `.yaml`, `.json`, `.properties`

**Blacklist Approach**:
- Unknown extensions are **allowed** unless explicitly excluded
- Only excludes: binaries, media, data files, archives, temp files

### Exclusion Lists

**Excluded**:
- Binaries: `.class`, `.jar`, `.dll`, `.exe`, `.so`, `.bin`
- Media: `.jpg`, `.png`, `.pdf`, `.doc`, `.xls`
- Data: `.dat`, `.db`, `.sqlite`, `.dump`
- Archives: `.zip`, `.tar`, `.gz`
- Temp: `.log`, `.tmp`, `.swp`

**Size Limits**:
- Non-source files: 1MB max
- Source files: No limit (exempted)
- Unknown extensions: MIME type check if >1MB

---

## Database Schema

### Key Tables

**`projects`**: Project metadata
- `id`, `name`, `domain`, `base_path`, `tech_stack`, `status`

**`source_files`**: File metadata
- `id`, `project_id`, `file_path`, `file_name`, `extension`, `storage_key`

**`symbols`**: Extracted code symbols
- `id`, `source_file_id`, `name`, `category`, `type`, `metadata`

**`global_dictionary`**: Business name mappings
- `id`, `technical_name`, `business_name`, `standard_label`, `domain`, `description`

**`aclf_mappings`**: ACLF to C structure mappings
- `id`, `aclf_tag`, `c_struct_name`, `c_field_name`, `offset`

---

## Deployment

### Local Development
```bash
docker-compose up -d
```

### Production (Kubernetes)
- Kubernetes manifests in `k8s/` directory
- Namespace: `decode-app`
- Services: All 8 services deployed as pods

### Environment Variables
- `AZURE_AD_TENANT_ID`: Azure AD tenant
- `AZURE_AD_CLIENT_ID`: Azure AD client ID
- `AZURE_AD_CLIENT_SECRET`: Azure AD secret
- `INTERNAL_LLM_GATEWAY_BASE_URL`: Internal LLM gateway URL
- `MINIO_ROOT_USER`: MinIO access key
- `MINIO_ROOT_PASSWORD`: MinIO secret key

---

## Future Enhancements

1. **Evaluation Framework**: Automated quality checks, drift detection
2. **Agent Memory**: Short-term and long-term memory for learning
3. **Graph Database**: Neo4j for relationship mapping
4. **Real-time Collaboration**: Multi-user analysis sessions
5. **Custom Personas**: User-defined worker personas

---

## Questions & Answers

### Q: How many agents are in the system?
**A**: 5 specialized agents:
1. Lexical Scout (domain discovery)
2. Head Architect (planning)
3. Worker Agents (5 personas, parallel execution)
4. QA Agent (quality assurance)
5. Business Analyst (synthesis)

### Q: How many modules/services?
**A**: 8 microservices:
1. ingestion-engine
2. code-parser
3. vectorizer-service
4. context-orchestrator
5. llm-gateway-service
6. web-frontend
7. api-gateway
8. postgres (database)

### Q: What is the analysis flow?
**A**: See "Complete Analysis Flow" section above. Summary:
1. Lexical Scout → Domain discovery
2. Head Architect → Planning
3. Workers → Parallel analysis (1-4 iterations)
4. QA Agent → Quality checks
5. Business Analyst → Final synthesis

### Q: What is AST?
**A**: Abstract Syntax Tree - tree representation of code structure. We use Tree-sitter to parse code into AST, then extract symbols (classes, methods, variables) for analysis.

### Q: What is domain extraction?
**A**: Lexical Scout automatically discovers business vocabulary (Order, Invoice, Product) and domain patterns (Order-to-Cash) from code without prior knowledge.

### Q: How is quality checked?
**A**: QA Agent performs 6 checks after each iteration:
- Evidence citations
- Schema drift
- Boundary contracts
- SRE risks
- Type mismatches
- Coverage validation

### Q: How is evidence score calculated?
**A**: Evidence Score = File Coverage (0-5) + Task Evidence (0-3) - Iteration Penalty (0-2)
- File Coverage: Based on files analyzed vs recommended
- Task Evidence: % of tasks with evidence sections
- Iteration Penalty: Deduction for early iterations

---

## Contact & Resources

- **Architecture Docs**: `docs/master_architecture.md`
- **Agent Flow**: `missions/AGENT_FLOW_ARCHITECTURE.md`
- **Exclusion Filter**: `ingestion-engine/EXCLUSION_FILTER.md`
- **Master Plan**: `.agent/rules/master-architecture.md`

---

**Last Updated**: January 2025  
**Version**: 1.0
