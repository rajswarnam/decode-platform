---
trigger: always_on
---

# Master Architecture Plan

| Phase | Focus | Primary Technologies | Key Deliverable |
| :--- | :--- | :--- | :--- |
| Foundation | Infrastructure | Native Binaries, MCP, Spring Boot | Governed Agent Environment |
| Analysis | Code Parsing | Tree-sitter, Java/COBOL Agents | Computable Project Map |
| Logic | Semantic Mapping | GPT-4o, Qdrant, Global Dictionary | Cross-Language Data Lineage |
| Interaction | UI & Docs | React, Context Orchestrator | Intelligent Developer Workbench |

This master plan outlines the strategic deployment of the Decode.AI platform, designed to provide Fortune 500-level code reverse engineering and knowledge management. The architecture is built on a governed agent ecosystem using the Model Context Protocol (MCP) to ensure modularity and scalability across legacy (C/COBOL) and modern (Java/Node) tech stacks.

## Phase 1: Infrastructure & Agent Foundation
**Goal**: Establish a robust, VDI-compatible backend capable of hosting specialized MCP servers.

### Step 1.1: Environment Scaffolding
*   **Execution**: Deploy **Hybrid Infrastructure**: Native binaries for Postgres (Host) and Docker containers for MinIO/Qdrant (via `setup-vdi.ps1`).
*   **Requirement**: Automated scripts must handle `host.docker.internal` networking to allow containers to talk to Native Postgres.
*   **Acceptance Criteria**: Services verified reachable via native ports; Postgres configured for JDBC session persistence.

### Step 1.2: Dual-Transport MCP Setup
*   **Execution**: Implement Spring AI MCP Server with a dual-transport strategy: stdio for local Mac/VDI testing and SSE (Server-Sent Events) for Kubernetes deployment.
*   **Requirement**: Integration of spring-ai-starter-mcp-server-webmvc.
*   **Acceptance Criteria**: Antigravity agents can call registered tools via standard input/output locally.

## Phase 2: Knowledge Ingestion & Computable Analysis
**Goal**: Transform raw source code into a queryable, semantic knowledge base.

### Step 2.1: Multi-Module & Throttled Ingestion
*   **Execution**: Coordinate Git clones and Zip uploads via the Ingestion Engine; support incremental subproject additions.
*   **Requirement**: MinIO for file storage; Spring Batch for multi-threaded file processing.
*   **File Ingestion Strategy**:
    *   **Blacklist Approach**: Files are ingested by default unless explicitly excluded (binary, media, data, archive, temp files).
    *   **Multi-Encoding Support**: Handles UTF-8, ISO-8859-1, Windows-1252 encodings to prevent `MalformedInputException`.
    *   **MIME Type Detection**: For large unknown extension files, performs MIME type check to allow text-based files.
    *   **Tech Stack Detection**: Automatically detects C/C++, ASP.NET, ACLF, Angular, COBOL, Java, Python, Node/React from source files.
*   **Token Management**:
    *   **TPM Governor Service**: Enforces 250k TPM limit with 220k proactive pause threshold (via jtokkit).
    *   **RPM Limiting**: 3000 requests per minute with 200ms minimum delay between requests.
    *   **Proactive Pause**: Pauses at 88% of TPM limit (220k) to prevent 429 errors, sends progress updates every 5s.
    *   **HTTP Timeout**: 600s (10 minutes) to handle rate limit pauses (94s) + LLM calls (60s) + multiple workers.
    *   **Retry Logic**: Exponential backoff for 429/420 errors (3s, 6s, 12s delays, max 3 attempts).
    *   **Data Privacy Filtering**: Redacts sensitive data (Credit Card, SSN, ITIN, EIN, Bank Routing, Phone, IP) before LLM calls.
*   **Async Queueing**: Process tasks via a persistent Postgres queue with exponential backoff on 429 errors.
*   **Acceptance Criteria**: Successful file extraction and metadata logging for polyglot repositories.

### Step 2.2: Polyglot Code Parser (Agentic Service)
*   **Execution**: Deploy a centralized `code-parser` agent implementing the **Polyglot Interface Pattern** (Rule 15).
*   **Capabilities**:
    *   **Automated Grammar Routing**: Dynamically select Tree-sitter grammars (Java, C, COBOL, TypeScript) based on file extension.
    *   **Unified AST Storage**: Normalize symbols from all languages into the common `symbols` table.
    *   **Multi-Encoding Support**: Handles UTF-8, ISO-8859-1, Windows-1252 to prevent parsing errors.
    *   **Robust Error Handling**: Gracefully handles null nodes, malformed code, and parsing exceptions.
    *   **Duplicate Prevention**: Skips re-parsing projects that already have symbols (configurable via `parser.auto-parse-on-startup: false`).
*   **ACLF Parsing**:
    *   **Dual Format Support**: Handles both XML and DSL (Domain-Specific Language) formats.
    *   **DSL Pattern Matching**: Extracts `ExternalDatalist`, `Datafield`, `Transaction`, `FormBlock`, `FormReport`, `Calculation` definitions.
    *   **Pre-validation**: Validates XML format before parsing to prevent `WstxUnexpectedCharException`.
*   **Requirement**: Spring AI MCP with registered `@Tool` functions for each language (`parseJava`, `parseC`, `parseAclf`).
*   **Acceptance Criteria**: Single JVM process successfully parsing mixed-language repositories without restart.
### Step 2.3: Vectorization & Embedding
*   **Execution**: Deploy `vectorizer-service` to generate embeddings and store in Qdrant.
*   **Capabilities**:
    *   **Duplicate Prevention**: Uses deterministic IDs (`symbol_id` as Qdrant point ID) to prevent duplicate vectorization.
    *   **Existence Check**: Verifies symbol already exists in Qdrant before adding (upsert behavior).
    *   **Local ONNX Embeddings**: Uses `all-MiniLM-L6-v2` model (86MB) for query embedding.
    *   **Startup Behavior**: Default `vectorizer.auto-vectorize-on-startup: false` to prevent duplicate work.
*   **Requirement**: ONNX Runtime for embeddings, Qdrant for vector storage.
*   **Acceptance Criteria**: Symbols vectorized once, retrievable via semantic search.

### Step 2.4: Vendor Interface & ACLF Mapping
*   **Execution**: Deploy a **Config-to-Logic Agent** to parse `.ACLF` and vendor config files.
*   **Logic**: Parse `.ACLF` XML/DSL configuration and perform in-memory joining with C-Struct symbols to populate `aclf_mappings`.
*   **Requirement**: Strict linkage between abstract Vendor Tags and physical Code Offsets.
*   **Acceptance Criteria**: Unified "Attribute-to-Storage" map linking configuration tags to source code symbols.

## Phase 3: Semantic Logic & Data Lineage
**Goal**: Map technical data flows to business-level use cases using the Global Dictionary.

### Step 3.1: Global Dictionary Mapping
*   **Execution**: Use GPT-4o to semantic cluster extracted fields (e.g., mapping CUST-ID to customer_id).
*   **Capabilities**:
    *   **Streaming Support**: Progress updates sent via SSE during dictionary population.
    *   **Status Tracking**: Uses `analysis_status` column (PENDING, COMPLETED, FAILED) to track mapping progress.
    *   **Batch Processing**: Processes symbols in batches with 300ms delay between LLM calls to respect rate limits.
    *   **Startup Behavior**: Default `dictionary.auto-populate-on-startup: false` to prevent duplicate work.
*   **Requirement**: Postgres `global_dictionary` table for centralized metadata storage (technical_name, business_name, domain, confidence_score).
*   **Acceptance Criteria**: Cross-language variables mapped with high accuracy; human-in-the-loop override UI available.

### Step 3.2: Cross-Platform Lineage Extraction
*   **Execution**: Automate data movement tracking from UI to backend using dictionary mapping.
*   **Requirement**: **Dual-Storage Strategy**: Store GPT-4o file summaries in **Postgres** (for UI display) AND **Qdrant** (for semantic search).
*   **Acceptance Criteria**: Proved end-to-end trace from a frontend input to a mainframe data record.

### Step 3.3: Integration Bridge Analysis (MQ to zConnect)
*   **Execution**: Map extracted C/MQ attributes to new zConnect JSON schemas.
*   **Requirement**: Auto-generate "Migration Mapping Specifications" by tracing Angular UI -> ASPX -> C Core -> MQ.
*   **Acceptance Criteria**: 80% reduction in manual discovery time for message attributes.

## Phase 4: User Interaction & Agentic Orchestration
**Goal**: Provide an intelligent workbench for developers to interrogate the codebase.

### Step 4.1: Use-Case Trace & Documentation
*   **Execution**: Deploy a Context Orchestrator agent to synthesize business logic flows into Markdown documentation.
*   **Agent Orchestration**:
    *   **Dynamic Worker Selection**: Selects workers based on project tech stack (C/C++, ASP.NET, ACLF, Angular, COBOL, Java, React).
    *   **Worker Personas**: BACKEND_JAVA, FRONTEND_REACT, DATABASE_SQL, LOGIC_EXTRACTOR, LEGACY_COBOL, BACKEND_C, BACKEND_ASPNET, CONFIG_ACLF, FRONTEND_HTML.
    *   **Intent Detection**: BUSINESS queries prioritize LOGIC_EXTRACTOR and DATABASE_SQL; TECHNICAL queries use specialized workers.
    *   **Iterative Refinement**: 1-4 iterations with QA checks, task refinement, and specialist spawning for gaps.
*   **Quality Assurance**:
    *   **Evidence Quality Scoring**: File coverage (0-5), task evidence percentage (0-3), iteration penalty (0-2).
    *   **QA Checks**: Code evidence citations, file diversity, module coverage, evidence quality, SRE risks, business logic completeness.
*   **Error Handling**:
    *   **Graceful Degradation**: Returns partial results on interruption/timeout.
    *   **InterruptedException Handling**: Detects interruptions via cause/message checks, restores thread status.
*   **Requirement**: RAG (Retrieval-Augmented Generation) pipeline integrated with Qdrant and Postgres.
*   **Acceptance Criteria**: Natural language queries yield accurate, cited explanations of business processes.

### Step 4.2: Enterprise Governance & Deployment
*   **Execution**: Refactor all services into multi-stage Docker builds for Kubernetes; integrate with company OAuth2.
*   **Requirement**: K8s manifests for decode-app namespace; RBAC for tool permissions.
*   **Acceptance Criteria**: Production-ready deployment verified in a secure enterprise VPC.
