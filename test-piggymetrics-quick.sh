#!/bin/bash
# Quick PiggyMetrics Test
# Simplified version that just runs the pipeline

set -e

echo "🐷 PiggyMetrics Quick Test"
echo "=========================="
echo ""

# Check current state
echo "📊 Current Database State:"
docker run --rm --add-host host.docker.internal:host-gateway postgres \
  psql -h host.docker.internal -U kothuparotta -d decode -t -c \
  "SELECT 
    (SELECT COUNT(*) FROM projects WHERE base_path LIKE '%piggymetrics%') as piggy_projects,
    (SELECT COUNT(*) FROM symbols) as total_symbols,
    (SELECT COUNT(*) FROM global_dictionary) as dictionary_entries;" 2>/dev/null || echo "Database query failed"

echo ""
echo "🚀 Running Pipeline Steps..."
echo ""

# Step 1: Parse PiggyMetrics (if not already done)
echo "📝 Step 1: Parsing Java code..."
docker run --rm \
  --add-host host.docker.internal:host-gateway \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:5432/decode \
  -e SPRING_DATASOURCE_USERNAME=kothuparotta \
  -e SPRING_DATASOURCE_PASSWORD= \
  -v $(pwd)/test-piggymetrics:/workspace/test-piggymetrics \
  code-parser:latest 2>&1 | grep -E "(Parsed|Processing Project|Finished|Saved)" | head -20 || echo "Parser completed"

echo ""

# Step 2: Reset symbols for LLM analysis
echo "🔄 Step 2: Preparing symbols for analysis..."
docker run --rm --add-host host.docker.internal:host-gateway postgres \
  psql -h host.docker.internal -U kothuparotta -d decode \
  -c "UPDATE symbols SET analysis_status = 'PENDING' WHERE analysis_status IS NULL OR analysis_status != 'COMPLETED';" 2>/dev/null

# Step 3: Run LLM Analysis
echo "🤖 Step 3: Running LLM semantic analysis..."
timeout 120 docker-compose run --rm context-orchestrator 2>&1 | grep -E "(Processing batch|Mapped:|All symbols)" | head -20 || echo "Orchestrator completed or timed out"

echo ""
echo "📊 Final Results:"
docker run --rm --add-host host.docker.internal:host-gateway postgres \
  psql -h host.docker.internal -U kothuparotta -d decode \
  -c "SELECT technical_name, business_name, standard_label FROM global_dictionary ORDER BY technical_name LIMIT 15;" 2>/dev/null

echo ""
echo "✅ Test Complete!"
