# Master Architecture Plan

| Phase | Focus | Status | Key Deliverable |
| :--- | :--- | :--- | :--- |
| Foundation | Infrastructure | **Verified** | Governed Agent Environment |
| Analysis | Code Parsing | **Functional** | Computable Project Map & ACLF Lookup |
| Logic | Semantic Mapping | **Active** | Cross-Language Data Lineage |
| Interaction | UI & Docs | **Active** | Intelligent Developer Workbench |

## Phase 1: Infrastructure & Agent Foundation (Implemented)
Establish a robust, VDI-compatible backend capable of hosting specialized MCP servers.

### Step 1.1: Environment Scaffolding
*   **Status**: **Implemented**. Hybrid Infrastructure using Native Postgres and Dockerized MinIO/Qdrant is live.
*   **Networking**: `host.docker.internal` successfully bridges containerized agents to the host database.

### Step 1.2: Dual-Transport MCP Setup
*   **Status**: **Implemented**. Spring AI MCP servers support `stdio` for local development and are ready for `SSE` deployment.

---

## Phase 2: Knowledge Ingestion & Computable Analysis (Implemented)
Transform raw source code into a queryable, semantic knowledge base.

### Step 2.1: Multi-Module & Throttled Ingestion
*   **Status**: **Verified**. Ingestion Engine handles Git/Local scanning with integrated **ExclusionService** to strip noise (.dat, .bin, .exe).

### Step 2.2: Specialized Language Agents
*   **Status**: **Verified**. Java, C, and COBOL (PoC) agents use Tree-sitter or Regex for AST/Knowledge extraction.

### 3.2 Context Orchestrator (Intelligence Layer)
*   **Semantic Explorer**: Orchestrates Qdrant/Postgres for cross-project lineage.
*   **Multi-Modal Ingestion Strategy (New)**: The framework utilizes a plug-and-play parser interface.
    *   **Module A (Legacy)**: ACLF/C/COBOL parsing for local source code.
    *   **Module B (Modern)**: Contract-driven analysis for distributed systems (YAML Schemas, Spring DTOs, React State).
*   **Context Stitching Engine (New)**: Bridges "Execution Holes" (e.g., Vendor IFrames) by correlating egress request state with ingress callback payloads using Session/Correlation IDs.
*   **Disambiguation Engine**: Resolves "Symbol Shadowing" in legacy code using [Weighted Scoring Signals](docs/architecture/trust-framework.md#3-the-weighted-disambiguation-engine-signal-logic).
*   **Trust Score Engine**: Aggregates confidence metrics. (See: [Trust Framework Specification](docs/architecture/trust-framework.md)).
*   **Blueprint Service**: Synthesizes the final MD/JSON Knowledge Maps.

### 3.3 Knowledge Sinks (Persistence)
*   **PostgreSQL**:
    *   `aclf_mappings`: The central repository for "Business-to-Binary" lineage.
    *   `projects`: Extended to store stateful metrics (`total_trust_score`, `ambiguity_count`).
    *   `global_dictionary`: Human-readable glossary of legacy symbols.
*   **MinIO**: High-speed cache for raw source snippets used in LLM verification.
*   **Qdrant**: Vector storage for semantic "Decision Patterns."

## 4. Language Support Matrix
| Language | Parser | Role |
| :--- | :--- | :--- |
| **C** | Tree-sitter | Monolith Logic / Struct Definitions |
| **COBOL** | Tree-sitter / Regex | Mainframe Ledger / Data Division extraction |
| **Java** | Tree-sitter | Modern Microservices / Target End-state |
| **ACLF** | XML Parser | The "Translator" (External-to-Internal) |

### Section: Polyglot Parser - ACLF Extension
*   **Status**: **Implemented**. The `AclfParserService` is a verified component of the Polyglot Code-Parser.

---

## Phase 3: Semantic Logic & Data Lineage (Complete)
Map technical data flows to business-level use cases using the Global Dictionary.

### Step 3.1: LLM Gateway Agent (Service)
*   **Status**: **Implemented**. `llm-gateway-service` acts as the central proxy/governor (250k TPM).

### Step 3.2: Ambiguity Intelligence
*   **Status**: **Complete**. Successfully demonstrated discovery and resolution of "Ghost Symbols" (e.g., 4 instances of `age`).

---

## Phase 4: User Interaction & Agentic Orchestration (Active)
Provide an intelligent workbench for developers to interrogate the codebase.

### Step 4.1: Knowledge Management Portal (React UI)
*   **Status**: **In-Progress**. A Next.js/React frontend visualizing the computable knowledge graph.
*   **Key Feature**: **Ambiguity Resolver** for human-in-the-loop conflict resolution.
*   **Display**: Uses "Logic Cards" to explain business rules in plain English.

### Step 4.2: Enterprise Governance & zConnect Bridge
*   **Status**: **Target Milestone**. Finalizing z/OS service discovery and JSON schema generation.
