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
*   **Token Management**:
    *   **TPM Governor Service**: Enforces a 200k TPM safety buffer (via tiktoken).
    *   **Boilerplate Stripping**: Local agents must strip comments/imports before LLM summarization.
    *   **Async Queueing**: Process tasks via a persistent Postgres queue with exponential backoff on 429 errors.
*   **Acceptance Criteria**: Successful file extraction and metadata logging for polyglot repositories.

### Step 2.2: Polyglot Code Parser (Agentic Service)
*   **Execution**: Deploy a centralized `code-parser` agent implementing the **Polyglot Interface Pattern** (Rule 15).
*   **Capabilities**:
    *   **Automated Grammar Routing**: Dynamically select Tree-sitter grammars (Java, C, COBOL) based on file extension.
    *   **Unified AST Storage**: Normalize symbols from all languages into the common `symbols` table.
*   **Requirement**: Spring AI MCP with registered `@Tool` functions for each language (`parseJava`, `parseC`).
*   **Acceptance Criteria**: Single JVM process successfully parsing mixed-language repositories without restart.
### Step 2.3: Vendor Interface & ACLF Mapping (Fusion Argo Tool)
*   **Execution**: Implement `mapAclfToC` as a Tool within the Polyglot Parser agent.
*   **Logic**: Parse `.ACLF` XML configuration and perform in-memory joining with C-Struct symbols to populate `aclf_mappings`.
*   **Requirement**: Strict linkage between abstract Vendor Tags and physical Code Offsets.
*   **Acceptance Criteria**: Automated generation of the "Attribute-to-Storage" lineage map.

### Step 2.4: Vendor Interface & ACLF Mapping
*   **Execution**: Deploy a **Config-to-Logic Agent** to parse `.ACLF` and vendor config files.
*   **Requirement**: Map abstract attribute tags (e.g., `PRIMARY_OWNER`) to physical C structure offsets.
*   **Acceptance Criteria**: Unified "Attribute-to-Storage" map linking configuration tags to source code symbols.

## Phase 3: Semantic Logic & Data Lineage
**Goal**: Map technical data flows to business-level use cases using the Global Dictionary.

### Step 3.1: Global Dictionary Mapping
*   **Execution**: Use GPT-4o to semantic cluster extracted fields (e.g., mapping CUST-ID to customer_id).
*   **Requirement**: Postgres Field_Aliases table for centralized metadata storage.
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
*   **Requirement**: RAG (Retrieval-Augmented Generation) pipeline integrated with Qdrant and Postgres.
*   **Acceptance Criteria**: Natural language queries yield accurate, cited explanations of business processes.

### Step 4.2: Enterprise Governance & Deployment
*   **Execution**: Refactor all services into multi-stage Docker builds for Kubernetes; integrate with company OAuth2.
*   **Requirement**: K8s manifests for decode-app namespace; RBAC for tool permissions.
*   **Acceptance Criteria**: Production-ready deployment verified in a secure enterprise VPC.
