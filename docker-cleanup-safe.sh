#!/bin/bash
# Safe Docker Cleanup (Auto-approved)
# Only removes clearly unused images

echo "🧹 Safe Docker Cleanup"
echo "======================"
echo ""

echo "📊 Before:"
docker system df
echo ""

echo "🗑️  Removing unused images (safe list)..."

# Remove BookPath images (different project)
docker rmi -f bookpathregistry.azurecr.io/bookpath-hq:v1 2>/dev/null && echo "  ✓ Removed bookpath-hq:v1 (3.26GB)" || true
docker rmi -f bookpathregistry.azurecr.io/bookpath-hq:v2 2>/dev/null && echo "  ✓ Removed bookpath-hq:v2 (3.26GB)" || true
docker rmi -f bookpathregistry.azurecr.io/bookpath-hq:v3 2>/dev/null && echo "  ✓ Removed bookpath-hq:v3 (3.26GB)" || true

# Remove Ollama (not used in Decode.AI)
docker rmi -f ollama/ollama:latest 2>/dev/null && echo "  ✓ Removed ollama (5.62GB)" || true

# Remove n8n (not used)
docker rmi -f docker.n8n.io/n8nio/n8n:latest 2>/dev/null && echo "  ✓ Removed n8n (1.4GB)" || true

# Remove AWS Lambda images (not used)
docker rmi -f amazon/aws-lambda-python:3.11 2>/dev/null && echo "  ✓ Removed aws-lambda-python (995MB)" || true
docker rmi -f public.ecr.aws/sam/build-python3.11:latest 2>/dev/null && echo "  ✓ Removed sam/build-python (2.57GB)" || true

# Remove duplicate ingestion-engine
docker rmi -f ingestion-engine:local 2>/dev/null && echo "  ✓ Removed ingestion-engine:local (duplicate)" || true

echo ""
echo "🗑️  Removing build cache..."
docker builder prune -f --filter "until=24h" > /dev/null 2>&1

echo ""
echo "✅ Cleanup Complete!"
echo ""
echo "📊 After:"
docker system df
