# Generated Documentation: Vectorizer Service

## Business Summary
**Vectorizer Service** is a core component of the semantic search pipeline. It transforms technical symbols into mathematical vectors (embeddings) to enable similarity search.

### Key Logic
*   **Input**: Symbols from PostgreSQL.
*   **Processing**: Uses ONNX Runtime (`all-MiniLM-L6-v2`) to generate 384-dimensional embeddings.
*   **Output**: Stores vectors in Qdrant (Collection: `symbols`).

## Technical Components
*   **Language**: Java 17 (Spring Boot)
*   **Dependencies**: `spring-ai-qdrant`, `spring-ai-transformers`
*   **Infrastructure**: Docker (Eclipse Temurin JRE), Qdrant (Vector DB)

## Detected Symbols
*   `VectorizerService`: Main service logic.
*   `McpConfig`: Configuration for Model Context Protocol (Deleted/Refactored).
*   `Symbol`: Domain entity representing a code symbol.
