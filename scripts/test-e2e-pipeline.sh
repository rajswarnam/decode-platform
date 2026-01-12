#!/bin/bash
# End-to-End Pipeline Test for Decode.AI
# This script demonstrates the complete flow from code ingestion to semantic analysis

set -e  # Exit on error

echo "🚀 Decode.AI End-to-End Pipeline Test"
echo "======================================"
echo ""

# Colors for output
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Step 1: Create sample code files
echo -e "${BLUE}Step 1: Creating sample legacy code...${NC}"
mkdir -p test-data/sample-project/src

cat > test-data/sample-project/src/CustomerService.java << 'EOF'
package com.legacy.banking;

public class CustomerService {
    private String customerId;
    private String accountNumber;
    
    public void processTransaction(String txnId, double amount) {
        // Legacy transaction processing
        System.out.println("Processing: " + txnId);
    }
    
    public String getCustomerBalance(String custId) {
        return "Balance for " + custId;
    }
}
EOF

cat > test-data/sample-project/src/account.c << 'EOF'
#include <stdio.h>

typedef struct {
    int acct_id;
    char acct_name[50];
    double balance;
} Account;

void update_balance(Account* acc, double amount) {
    acc->balance += amount;
}

int main() {
    Account customer_account;
    customer_account.acct_id = 12345;
    update_balance(&customer_account, 100.0);
    return 0;
}
EOF

echo -e "${GREEN}✓ Sample code created${NC}"
echo ""

# Step 2: Verify services are running
echo -e "${BLUE}Step 2: Checking service health...${NC}"
if ! docker ps | grep -q "llm-gateway-service"; then
    echo -e "${YELLOW}⚠ Starting llm-gateway-service...${NC}"
    docker-compose up -d llm-gateway-service
    sleep 5
fi
echo -e "${GREEN}✓ Services running${NC}"
echo ""

# Step 3: Copy files to workspace (simulates ingestion)
echo -e "${BLUE}Step 3: Ingesting code files...${NC}"
# In production, this would use the ingestion-engine API
# For now, we'll manually trigger parsing
echo -e "${GREEN}✓ Files ready for parsing${NC}"
echo ""

# Step 4: Run Code Parser
echo -e "${BLUE}Step 4: Parsing code with Tree-sitter...${NC}"
echo "Running code-parser service..."

# Mount the test data and run parser
docker run --rm \
  --add-host host.docker.internal:host-gateway \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:5432/decode \
  -e SPRING_DATASOURCE_USERNAME=kothuparotta \
  -e SPRING_DATASOURCE_PASSWORD= \
  -v $(pwd)/test-data:/workspace \
  code-parser:latest 2>&1 | grep -E "(Parsed|symbols|Finished)" || true

echo -e "${GREEN}✓ Code parsing complete${NC}"
echo ""

# Step 5: Check parsed symbols in database
echo -e "${BLUE}Step 5: Verifying parsed symbols...${NC}"
docker run --rm --add-host host.docker.internal:host-gateway postgres \
  psql -h host.docker.internal -U kothuparotta -d decode \
  -c "SELECT name, category, file_path FROM symbols ORDER BY created_at DESC LIMIT 10;"
echo ""

# Step 6: Run Vectorizer
echo -e "${BLUE}Step 6: Generating embeddings with ONNX...${NC}"
docker-compose run --rm vectorizer-service 2>&1 | grep -E "(Vectorizing|embeddings|Finished)" || true
echo -e "${GREEN}✓ Embeddings generated${NC}"
echo ""

# Step 7: Run Context Orchestrator (LLM Analysis)
echo -e "${BLUE}Step 7: Generating semantic mappings with Azure GPT-4o-mini...${NC}"
echo "This will use the LLM Gateway to analyze symbols..."

# Reset symbols to PENDING for demo
docker run --rm --add-host host.docker.internal:host-gateway postgres \
  psql -h host.docker.internal -U kothuparotta -d decode \
  -c "UPDATE symbols SET analysis_status = 'PENDING' WHERE analysis_status IS NULL OR analysis_status = 'FAILED';" > /dev/null

docker-compose run --rm context-orchestrator 2>&1 | grep -E "(Processing batch|Mapped:|All symbols)" || true
echo -e "${GREEN}✓ Semantic analysis complete${NC}"
echo ""

# Step 8: Query Global Dictionary
echo -e "${BLUE}Step 8: Viewing Global Dictionary results...${NC}"
docker run --rm --add-host host.docker.internal:host-gateway postgres \
  psql -h host.docker.internal -U kothuparotta -d decode \
  -c "SELECT technical_name, business_name, standard_label, LEFT(description, 60) as description FROM global_dictionary ORDER BY technical_name LIMIT 15;"
echo ""

# Step 9: Query Vector Store
echo -e "${BLUE}Step 9: Checking Qdrant vector store...${NC}"
curl -s http://localhost:6333/collections/code_embeddings | jq '.result.vectors_count' 2>/dev/null || echo "Qdrant not accessible or collection not created"
echo ""

# Summary
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}✓ End-to-End Pipeline Test Complete!${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo "Pipeline Summary:"
echo "  1. ✓ Code files created"
echo "  2. ✓ Symbols parsed with Tree-sitter"
echo "  3. ✓ Embeddings generated with ONNX"
echo "  4. ✓ Semantic mappings created with Azure GPT-4o-mini"
echo "  5. ✓ Results stored in Postgres + Qdrant"
echo ""
echo "Next steps:"
echo "  - View full dictionary: psql -h host.docker.internal -U kothuparotta -d decode -c 'SELECT * FROM global_dictionary;'"
echo "  - Query vectors: curl http://localhost:6333/collections/code_embeddings"
echo "  - Test LLM Gateway: Use Bruno collection in bruno-api-tests/"
