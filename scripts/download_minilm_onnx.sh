#!/usr/bin/env bash
set -euo pipefail

# Resolve repo root (script location is repo/scripts)
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODEL_DIR="$ROOT_DIR/infra/models/all-MiniLM-L6-v2"

mkdir -p "$MODEL_DIR"

echo "Downloading MiniLM ONNX model and tokenizer to: $MODEL_DIR"

# Hugging Face Xenova export of all-MiniLM-L6-v2
curl -L -o "$MODEL_DIR/model.onnx" \
  https://huggingface.co/Xenova/all-MiniLM-L6-v2/resolve/main/model.onnx
curl -L -o "$MODEL_DIR/tokenizer.json" \
  https://huggingface.co/Xenova/all-MiniLM-L6-v2/resolve/main/tokenizer.json
curl -L -o "$MODEL_DIR/config.json" \
  https://huggingface.co/Xenova/all-MiniLM-L6-v2/resolve/main/config.json

echo "Done. Files in $MODEL_DIR:"
ls -lh "$MODEL_DIR"
