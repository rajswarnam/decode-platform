#!/bin/bash
# Test the Semantic Explorer (RAG)

set -e

echo "🚀 Starting Semantic Explorer (RAG) Test"
echo "======================================="

# 1. Rebuild and Start Context Orchestrator
echo "🏗️  Rebuilding Context Orchestrator..."
docker-compose build context-orchestrator

echo "⏳ Starting Context Orchestrator..."
docker-compose up -d context-orchestrator

# Wait for service to be ready
echo "🕒 Waiting for service to initialize (20s)..."
sleep 20

# 2. Execute Query
echo "🔍 Querying: 'How is OAuth2 authorization configured?'"
RESPONSE=$(curl -s -X POST http://localhost:8082/v1/explore/query \
  -H "Content-Type: application/json" \
  -d '{
    "query": "How is OAuth2 authorization configured in the auth-service?",
    "domain": "Retail Banking"
  }')

echo "📝 Response from Semantic Explorer:"
echo "$RESPONSE" | jq '.'

# Cleanup
docker-compose stop context-orchestrator || true

echo "✅ Test Complete!"
