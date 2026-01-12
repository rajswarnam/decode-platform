#!/bin/bash
# Docker Cleanup Script for Decode.AI
# Removes unused images, containers, and build cache

set -e

echo "🧹 Docker Cleanup for Decode.AI"
echo "================================"
echo ""

# Show current state
echo "📊 Current Docker Usage:"
docker system df
echo ""

# Confirm cleanup
read -p "⚠️  This will remove unused images and build cache. Continue? (y/N): " -n 1 -r
echo ""
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    echo "Cleanup cancelled."
    exit 0
fi

echo ""
echo "🗑️  Step 1: Removing dangling images..."
docker image prune -f

echo ""
echo "🗑️  Step 2: Removing unused images..."
# Remove specific unused images
docker rmi bookpathregistry.azurecr.io/bookpath-hq:v1 2>/dev/null || true
docker rmi bookpathregistry.azurecr.io/bookpath-hq:v2 2>/dev/null || true
docker rmi bookpathregistry.azurecr.io/bookpath-hq:v3 2>/dev/null || true
docker rmi ollama/ollama:latest 2>/dev/null || true
docker rmi docker.n8n.io/n8nio/n8n:latest 2>/dev/null || true
docker rmi amazon/aws-lambda-python:3.11 2>/dev/null || true
docker rmi public.ecr.aws/sam/build-python3.11:latest 2>/dev/null || true

# Remove duplicate decode-workspace images (keep only latest)
docker rmi decode-workspace-context-orchestrator:latest 2>/dev/null || true
docker rmi decode-workspace-vectorizer-service:latest 2>/dev/null || true
docker rmi ingestion-engine:local 2>/dev/null || true

echo ""
echo "🗑️  Step 3: Removing build cache..."
docker builder prune -f

echo ""
echo "🗑️  Step 4: Removing unused volumes..."
docker volume prune -f

echo ""
echo "✅ Cleanup Complete!"
echo ""
echo "📊 New Docker Usage:"
docker system df
echo ""

# Calculate savings
echo "💰 Space Reclaimed:"
echo "   Check the 'RECLAIMABLE' column above"
