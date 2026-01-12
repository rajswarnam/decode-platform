@echo off
REM Cross-Platform Pre-Ingestion Report (Windows)
REM Works on: Windows 10/11, Windows Server

REM Detect Java
where java >nul 2>&1
if %ERRORLEVEL% EQU 0 (
    set JAVA_CMD=java
) else if defined JAVA_HOME (
    set JAVA_CMD=%JAVA_HOME%\bin\java.exe
) else (
    echo ❌ Error: Java not found. Please install Java 17+ or set JAVA_HOME.
    exit /b 1
)

REM Compile if needed
set TOOL_CLASS=com.decode.ingestion.tool.PreIngestionReport
set TOOL_SRC=ingestion-engine\src\main\java\com\decode\ingestion\tool\PreIngestionReport.java
set TOOL_BIN=ingestion-engine\target\classes

if exist "%TOOL_SRC%" (
    echo 🔨 Compiling Pre-Ingestion Report tool...
    if not exist "%TOOL_BIN%" mkdir "%TOOL_BIN%"
    javac -d "%TOOL_BIN%" "%TOOL_SRC%" 2>nul
    if %ERRORLEVEL% NEQ 0 (
        echo ⚠️  Compilation failed. Using pre-built version if available.
    )
)

REM Run the tool
set TARGET_DIR=%1
if "%TARGET_DIR%"=="" set TARGET_DIR=.

echo.
%JAVA_CMD% -cp "%TOOL_BIN%" %TOOL_CLASS% "%TARGET_DIR%"
