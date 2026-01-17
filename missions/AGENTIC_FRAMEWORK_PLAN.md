# Decode.AI Protocol: "The Hive Pattern"
## Implementation Plan for Agentic Parallel Framework

**Objective:** Transform the `SemanticExplorer` from a linear RAG tool into a multi-agent orchestration engine capable of "Deep Functional DNA Mapping."

### 1. Architectural Overview
We will implement a **Parallel Map-Reduce with Dual-Validation** pattern.

*   **Head Architect (Planner)**: Analyzes Project Structure -> Generates Execution Plan.
*   **Worker Swarm (Executors)**: Language-Aware Agents (Java, React, SQL) executing in PARALLEL.
*   **Architect (Validator)**: Unit-tests worker outputs against the Plan constraints.
*   **QA Lead (Gatekeeper)**: Verifies cross-worker consistency.
*   **Feedback Loop**: Auto-correction retry mechanics (Max 3 attempts).

### 2. Component Design

#### A. `AgentOrchestrator` (New Service)
*   **Responsibility**: Managing the ThreadPool (Virtual Threads), Dispatching Tasks, Aggregating Results.
*   **Key Methods**:
    *   `CompletableFuture<AnalysisReport> dispatchSwarm(Plan plan)`
    *   `ValidationResult validateWorkerOutput(WorkerTask task, String output)`

#### B. `WorkerFactory` (New Component)
*   **Responsibility**: Selecting the right Persona and Prompt based on file type.
*   **Personas**:
    *   `BACKEND_JAVA`: Focus on `@RestController`, `Service`, `Entity`.
    *   `FRONTEND_REACT`: Focus on `Redux`, `Axios`, `Components`.
    *   `DATABASE_SQL`: Focus on `DDL`, `Constraints`.
    *   `LEGACY_COBOL`: Focus on `WORKING-STORAGE`, `CICS`.

#### C. `QAGatekeeper` (New Component)
*   **Responsibility**: Generating specific validation checklists based on the Plan to detect hallucinations or gaps.

### 3. Execution Flow (The "Protocol")

1.  **User Trigger**: "Map the DNA of this project."
2.  **Phase 1: Planning**
    *   Architect scans `ProjectRepository` (Modules, Tech Stack).
    *   LLM generates `ExecutionPlan` (JSON): `[ { "role": "JAVA", "focus": "procurement-backend" }, { "role": "REACT", "focus": "frontend" } ]`
3.  **Phase 2: The Swarm (Parallel)**
    *   `AgentOrchestrator` spawns threads.
    *   Each Worker performs targeted `VectorSearch` (RAG) for its specific focus.
    *   LLM generates domain-specific sub-reports.
4.  **Phase 3: Architect Review (Unit Test)**
    *   Architect checks: "Did Java Worker cite specific files?"
    *   *Retry Loop:* If fail, re-dispatch with broader search.
5.  **Phase 4: QA Gatekeeping**
    *   QA checks: "Does Backend 'Auth' match Frontend 'Login'?"
    *   *Retry Loop:* If fail, send feedback to Architect for synthesis correction.
6.  **Phase 5: Final Synthesis**
    *   Merge approved reports into Final Markdown Blueprint.

### 4. Implementation Steps

- [ ] **Step 1**: Create `com.decode.context.orchestrator.agent` package.
- [ ] **Step 2**: Define `WorkerPersona` Enum and `WorkerTask` POJO.
- [ ] **Step 3**: Implement `AgentOrchestrator` service with `VirtualThread` executor.
- [ ] **Step 4**: Implement `Planner` logic (Project Structure -> JSON Plan).
- [ ] **Step 5**: Implement `SemanticExplorerService` integration (The entry point).
- [ ] **Step 6**: UI Feedback Stream (Ensure user sees "Architect dispatching Java Agent...").

### 5. Future Extensibility
This framework supports adding new "Specialists" (e.g., specific to Mainframe or SAP) without changing the core orchestration logic.
