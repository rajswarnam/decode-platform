#!/bin/sh
# Docker-based Pre-Ingestion Report (100% Cross-Platform)
# Works on: Any system with Docker

TARGET_DIR="${1:-.}"

echo "📊 Running Pre-Ingestion Report (Docker)..."
echo ""

docker run --rm \
  -v "$(pwd):/workspace" \
  -w /workspace \
  maven:3.9-eclipse-temurin-17 \
  sh -c "
    cd ingestion-engine && \
    mvn -q compile && \
    java -cp target/classes com.decode.ingestion.tool.PreIngestionReport '$TARGET_DIR'
  "
