#!/bin/bash
# scripts/setup_lfs.sh

echo "📦 Setting up Git LFS for Large Models"

# 1. Install Git LFS (if not present)
if ! command -v git-lfs &> /dev/null; then
    echo "❌ Git LFS is not installed."
    echo "   Mac: brew install git-lfs"
    echo "   Ubuntu: sudo apt-get install git-lfs"
    echo "   Windows: git lfs install"
    exit 1
fi

# 2. Initialize LFS in the repo
git lfs install
echo "✅ Git LFS initialized."

# 3. Track ONNX models
echo "🔍 Tracking .onnx files..."
git lfs track "*.onnx"
git add .gitattributes

# 4. Check for existing large files
echo "🛠 Checks for existing files..."
git lfs ls-files

echo "✅ Setup Complete. Future commits of .onnx files will be handled by LFS."
echo "NOTE: If you have already committed large files, you may need to run 'git lfs migrate' to rewrite history."
