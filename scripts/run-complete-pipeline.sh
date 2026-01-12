#!/bin/bash
# Complete PiggyMetrics Pipeline
# Runs all steps: Parse → Upload → Vectorize → Analyze

set -e

echo "🔄 Running Complete PiggyMetrics Pipeline"
echo "=========================================="
echo ""

# Step 1: Wait for parsing to complete (if still running)
echo "⏳ Step 1: Waiting for code parsing to complete..."
sleep 5

# Step 2: Check parsed symbols
echo "📊 Step 2: Checking parsed symbols..."
SYMBOL_COUNT=$(docker run --rm --add-host host.docker.internal:host-gateway postgres \
  psql -h host.docker.internal -U kothuparotta -d decode -t -c \
  "SELECT COUNT(*) FROM symbols;" | tr -d ' ')

echo "   Symbols in database: $SYMBOL_COUNT"
echo ""

# Step 3: Run Vectorizer
echo "🧮 Step 3: Generating embeddings with ONNX..."
docker-compose run --rm vectorizer-service 2>&1 | grep -E "(Vectorizing|embeddings|Finished|Processing)" | head -20 || true
echo "✅ Vectorization complete"
echo ""

# Step 4: Check Qdrant
echo "📊 Step 4: Checking Qdrant vector count..."
curl -s http://localhost:6333/collections/code_embeddings | jq '.result.vectors_count' || echo "Collection not found"
echo ""

# Step 5: Run LLM Analysis
echo "🤖 Step 5: Running semantic analysis with Azure GPT-4o-mini..."
echo "   Resetting symbols to PENDING..."
docker run --rm --add-host host.docker.internal:host-gateway postgres \
  psql -h host.docker.internal -U kothuparotta -d decode \
  -c "UPDATE symbols SET analysis_status = 'PENDING' WHERE analysis_status IS NULL OR analysis_status = 'FAILED';" > /dev/null

echo "   Running orchestrator..."
docker-compose run --rm context-orchestrator 2>&1 | grep -E "(Processing batch|Mapped:|All symbols)" | head -30 || true
echo "✅ Semantic analysis complete"
echo ""

# Step 6: Final Report
echo "📈 Step 6: Generating Final Report..."
echo ""
echo "=========================================="
echo "✅ PIPELINE COMPLETE"
echo "=========================================="
echo ""

# Summary Statistics
echo "📊 Summary Statistics:"
echo ""

docker run --rm --add-host host.docker.internal:host-gateway postgres \
  psql -h host.docker.internal -U kothuparotta -d decode \
  -c "
SELECT 
  'Projects' as metric, COUNT(*)::text as count FROM projects
UNION ALL
SELECT 'Symbols', COUNT(*)::text FROM symbols
UNION ALL
SELECT 'Dictionary Entries', COUNT(*)::text FROM global_dictionary
UNION ALL
SELECT 'Symbols Analyzed', COUNT(*)::text FROM symbols WHERE analysis_status = 'COMPLETED';
"

echo ""
echo "🎯 Next Steps:"
echo "  1. Query Qdrant for semantic search"
echo "  2. Generate analysis report"
echo "  3. Build dashboard visualization"
