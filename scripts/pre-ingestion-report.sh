#!/bin/sh
# Cross-Platform Pre-Ingestion Report Wrapper
# Works on: Windows (Git Bash/WSL), Linux, macOS, Alpine

# Detect Java
if command -v java >/dev/null 2>&1; then
    JAVA_CMD=java
elif [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA_CMD="$JAVA_HOME/bin/java"
else
    echo "❌ Error: Java not found. Please install Java 17+ or set JAVA_HOME."
    exit 1
fi

# Compile if needed
TOOL_CLASS="com.decode.ingestion.tool.PreIngestionReport"
TOOL_SRC="ingestion-engine/src/main/java/com/decode/ingestion/tool/PreIngestionReport.java"
TOOL_BIN="ingestion-engine/target/classes"

if [ -f "$TOOL_SRC" ]; then
    echo "🔨 Compiling Pre-Ingestion Report tool..."
    mkdir -p "$TOOL_BIN"
    javac -d "$TOOL_BIN" "$TOOL_SRC" 2>/dev/null || {
        echo "⚠️  Compilation failed. Using pre-built version if available."
    }
fi

# Run the tool
TARGET_DIR="${1:-.}"
echo ""
$JAVA_CMD -cp "$TOOL_BIN" $TOOL_CLASS "$TARGET_DIR"
