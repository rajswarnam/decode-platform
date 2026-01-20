#!/bin/bash

# Reset Decode Platform - Clean Database, Qdrant, and MinIO
# This script will:
# 1. Stop all services
# 2. Clean PostgreSQL database (drop and recreate)
# 3. Clean Qdrant collection (delete all vectors)
# 4. Clean MinIO bucket (delete all files)
# 5. Restart services

set -e

echo "⚠️  WARNING: This will DELETE ALL DATA from:"
echo "   - PostgreSQL database (projects, symbols, files, etc.)"
echo "   - Qdrant vector store (all embeddings)"
echo "   - MinIO bucket (all uploaded files)"
echo ""
read -p "Are you sure you want to proceed? (yes/no): " confirm

if [ "$confirm" != "yes" ]; then
    echo "❌ Reset cancelled."
    exit 0
fi

echo ""
echo "🛑 Stopping all services..."
docker-compose down

echo ""
echo "🗑️  Cleaning PostgreSQL database..."
# Remove PostgreSQL data volume
if [ -d "./infra/postgres_data" ]; then
    echo "   Removing postgres_data directory..."
    rm -rf ./infra/postgres_data
    echo "   ✅ PostgreSQL data cleaned"
else
    echo "   ℹ️  postgres_data directory not found (already clean)"
fi

echo ""
echo "🗑️  Cleaning Qdrant vector store..."
# Remove Qdrant storage volume
if [ -d "./infra/qdrant_storage" ]; then
    echo "   Removing qdrant_storage directory..."
    rm -rf ./infra/qdrant_storage
    echo "   ✅ Qdrant storage cleaned"
else
    echo "   ℹ️  qdrant_storage directory not found (already clean)"
fi

echo ""
echo "🗑️  Cleaning MinIO bucket..."
# Remove MinIO data volume
if [ -d "./infra/minio_data" ]; then
    echo "   Removing minio_data directory..."
    rm -rf ./infra/minio_data
    echo "   ✅ MinIO data cleaned"
else
    echo "   ℹ️  minio_data directory not found (already clean)"
fi

echo ""
echo "🚀 Starting services..."
docker-compose up -d postgres qdrant minio

echo ""
echo "⏳ Waiting for services to be ready..."
sleep 10

# Wait for PostgreSQL to be ready
echo "   Waiting for PostgreSQL..."
until docker-compose exec -T postgres pg_isready -U decode_user -d decode > /dev/null 2>&1; do
    echo "   ..."
    sleep 2
done
echo "   ✅ PostgreSQL is ready"

# Wait for Qdrant to be ready
echo "   Waiting for Qdrant..."
until curl -s http://localhost:6333/health > /dev/null 2>&1; do
    echo "   ..."
    sleep 2
done
echo "   ✅ Qdrant is ready"

# Wait for MinIO to be ready
echo "   Waiting for MinIO..."
until curl -s http://localhost:9000/minio/health/live > /dev/null 2>&1; do
    echo "   ..."
    sleep 2
done
echo "   ✅ MinIO is ready"

echo ""
echo "✅ Reset complete! All data has been cleaned."
echo ""
echo "📋 Next steps:"
echo "   1. Re-upload your project files via the UI or API"
echo "   2. The system will parse, vectorize, and index everything fresh"
echo "   3. With the new fixes, duplicates will be prevented"
echo ""
echo "🚀 Starting all services..."
docker-compose up -d

echo ""
echo "✨ Done! Services are starting. Check logs with: docker-compose logs -f"
