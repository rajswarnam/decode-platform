#!/bin/bash

# Clean Qdrant Only - Remove duplicate vectors without touching database or MinIO
# This script will:
# 1. Delete the Qdrant collection (removes all vectors)
# 2. Restart vectorizer-service to recreate collection
# 3. Re-vectorize all symbols from database (with duplicate prevention)

set -e

echo "⚠️  WARNING: This will DELETE ALL VECTORS from Qdrant."
echo "   - Database (PostgreSQL) will NOT be touched"
echo "   - MinIO files will NOT be touched"
echo "   - Only Qdrant vectors will be deleted"
echo ""
read -p "Are you sure you want to proceed? (yes/no): " confirm

if [ "$confirm" != "yes" ]; then
    echo "❌ Clean cancelled."
    exit 0
fi

echo ""
echo "🛑 Stopping vectorizer-service..."
docker-compose stop vectorizer-service

echo ""
echo "🗑️  Cleaning Qdrant collection..."
# Option 1: Delete collection via Qdrant API
echo "   Deleting 'symbols' collection from Qdrant..."
curl -X DELETE http://localhost:6333/collections/symbols 2>/dev/null || echo "   ℹ️  Collection may not exist yet"

# Option 2: Remove Qdrant storage (more thorough)
if [ -d "./infra/qdrant_storage" ]; then
    echo "   Removing qdrant_storage directory..."
    rm -rf ./infra/qdrant_storage
    echo "   ✅ Qdrant storage cleaned"
else
    echo "   ℹ️  qdrant_storage directory not found (already clean)"
fi

echo ""
echo "🚀 Restarting Qdrant..."
docker-compose restart qdrant

echo ""
echo "⏳ Waiting for Qdrant to be ready..."
until curl -s http://localhost:6333/health > /dev/null 2>&1; do
    echo "   ..."
    sleep 2
done
echo "   ✅ Qdrant is ready"

echo ""
echo "🚀 Restarting vectorizer-service..."
docker-compose up -d vectorizer-service

echo ""
echo "⏳ Waiting for vectorizer-service to be ready..."
sleep 10

echo ""
echo "🔄 Re-vectorizing all symbols from database..."
echo "   This will use the new duplicate prevention logic"
echo "   Symbols will be vectorized with deterministic IDs"

# Trigger vectorization for all projects
# Get all project IDs from database
PROJECT_IDS=$(docker-compose exec -T postgres psql -U decode_user -d decode -t -c "SELECT id::text FROM projects;" | tr -d ' ' | grep -v '^$')

if [ -z "$PROJECT_IDS" ]; then
    echo "   ℹ️  No projects found in database. Upload projects first."
else
    for PROJECT_ID in $PROJECT_IDS; do
        echo "   Triggering vectorization for project: $PROJECT_ID"
        curl -X POST "http://localhost:8084/api/vectorizer/trigger?projectId=$PROJECT_ID" 2>/dev/null || echo "   ⚠️  Failed to trigger for $PROJECT_ID"
    done
    echo "   ✅ Vectorization triggered for all projects"
fi

echo ""
echo "✅ Qdrant cleanup complete!"
echo ""
echo "📋 What happened:"
echo "   ✅ Qdrant vectors deleted (duplicates removed)"
echo "   ✅ Database preserved (all symbols still exist)"
echo "   ✅ MinIO files preserved (all source files still exist)"
echo "   ✅ Re-vectorization triggered (with duplicate prevention)"
echo ""
echo "💡 Check vectorizer logs: docker-compose logs -f vectorizer-service"
