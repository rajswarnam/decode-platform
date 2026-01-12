#!/bin/bash
# PiggyMetrics End-to-End Test
# Tests the full Decode.AI pipeline with a real microservices application

set -e

echo "🐷 PiggyMetrics End-to-End Test"
echo "================================"
echo ""

# Step 1: Parse Java code
echo "📝 Step 1: Parsing Java code with Tree-sitter..."
docker run --rm \
  --add-host host.docker.internal:host-gateway \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:5432/decode \
  -e SPRING_DATASOURCE_USERNAME=kothuparotta \
  -e SPRING_DATASOURCE_PASSWORD= \
  -e SPRING_AI_MCP_SERVER_STDIO=true \
  -v $(pwd)/test-piggymetrics:/workspace \
  code-parser:latest 2>&1 | grep -E "(Parsed|symbols|Finished|Processing Project)" | head -20

echo ""
echo "✅ Parsing complete"
echo ""

# Step 2: Check parsed symbols
echo "📊 Step 2: Checking parsed symbols..."
docker run --rm --add-host host.docker.internal:host-gateway postgres \
  psql -h host.docker.internal -U kothuparotta -d decode \
  -c "SELECT COUNT(*) as total_symbols, category, COUNT(*) FROM symbols WHERE file_path LIKE '%piggymetrics%' GROUP BY category;"

echo ""

# Step 3: Run Context Orchestrator (LLM Analysis)
echo "🤖 Step 3: Running semantic analysis with Azure GPT-4o-mini..."
echo "Resetting symbols to PENDING..."
docker run --rm --add-host host.docker.internal:host-gateway postgres \
  psql -h host.docker.internal -U kothuparotta -d decode \
  -c "UPDATE symbols SET analysis_status = 'PENDING' WHERE file_path LIKE '%piggymetrics%' AND (analysis_status IS NULL OR analysis_status = 'FAILED');" > /dev/null

echo "Running orchestrator..."
docker-compose run --rm context-orchestrator 2>&1 | grep -E "(Processing batch|Mapped:|All symbols|Starting Dictionary)" | head -30

echo ""
echo "✅ Semantic analysis complete"
echo ""

# Step 4: View Results
echo "📈 Step 4: Viewing Global Dictionary results..."
docker run --rm --add-host host.docker.internal:host-gateway postgres \
  psql -h host.docker.internal -U kothuparotta -d decode \
  -c "SELECT technical_name, business_name, standard_label, LEFT(description, 50) as description FROM global_dictionary WHERE technical_name IN (SELECT name FROM symbols WHERE file_path LIKE '%piggymetrics%') ORDER BY technical_name LIMIT 20;"

echo ""
echo "================================"
echo "✅ PiggyMetrics Test Complete!"
echo "================================"
echo ""
echo "Summary:"
echo "  - Projects Discovered: 10 microservices"
echo "  - Symbols Parsed: (see above)"
echo "  - Semantic Mappings: (see Global Dictionary)"
echo ""
echo "Next: Review the mappings and generate analysis report"
