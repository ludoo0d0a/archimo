#!/bin/sh

set -eu

# Simple installer/runner for Archimo.
# - Downloads the latest archimo.jar to the current directory
# - Optionally compiles the current Maven project (skip tests)
# - Uses JAVA_HOME if set, otherwise falls back to java on PATH

VERSION=1.2.0

JAR_NAME="${ARCHIMO_JAR:-archimo.jar}"
RELEASE_URL="${ARCHIMO_URL:-https://github.com/ludoo0d0a/archimo/releases/download/v$VERSION/archimo-release.jar}"

# Default positional arguments
POS_PROJECT_DIR="."
POS_SKIP_TEXT="true"
POS_RECOMPILE="false"

# Simple positional argument parsing (max 3, if not starting with -)
if [ $# -ge 1 ] && ! echo "$1" | grep -q '^-'; then
  POS_PROJECT_DIR="$1"
  shift
  if [ $# -ge 1 ] && ! echo "$1" | grep -q '^-'; then
    POS_SKIP_TEXT="$1"
    shift
    if [ $# -ge 1 ] && ! echo "$1" | grep -q '^-'; then
      POS_RECOMPILE="$1"
      shift
    fi
  fi
fi

# Make PROJECT_DIR absolute before we cd into it
PROJECT_DIR="$(cd "$POS_PROJECT_DIR" >/dev/null 2>&1 && pwd || echo "$POS_PROJECT_DIR")"

# Make JAR_NAME absolute so it's accessible after cd
case "$JAR_NAME" in
  /*) JAR_PATH="$JAR_NAME" ;;
  *)  JAR_PATH="$(pwd)/$JAR_NAME" ;;
esac

if [ -f "$JAR_NAME" ] && [ -s "$JAR_NAME" ] && [ "${ARCHIMO_FORCE_DOWNLOAD:-0}" != "1" ]; then
  echo "Using local jar: $JAR_NAME"
else
  echo "Downloading Archimo from:"
  echo "  $RELEASE_URL"
  echo "  -> $JAR_NAME"

  if command -v wget >/dev/null 2>&1; then
    wget -q -O "$JAR_NAME" "$RELEASE_URL"
  elif command -v curl >/dev/null 2>&1; then
    curl -fsSL "$RELEASE_URL" -o "$JAR_NAME"
  else
    echo "ERROR: Neither wget nor curl is available. Please install one of them." >&2
    exit 1
  fi
fi

# Build logic moved to the Java JAR (Project Mode).
# We only ensure we are in the project directory if it was provided positionally.

JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/java}"
if [ -z "${JAVA_BIN:-}" ]; then
  JAVA_BIN="java"
fi

cd "$PROJECT_DIR"

# Parse CLI args to find where the HTML report was generated.
# We keep this intentionally simple and only handle --output-dir / --project-dir.
OUTPUT_DIR_ARG=""
PROJECT_DIR_ARG=""
NEXT_IS_OUTPUT_DIR=0
NEXT_IS_PROJECT_DIR=0
for arg in "$@"; do
  if [ "$NEXT_IS_OUTPUT_DIR" -eq 1 ]; then OUTPUT_DIR_ARG="$arg"; NEXT_IS_OUTPUT_DIR=0; continue; fi
  if [ "$NEXT_IS_PROJECT_DIR" -eq 1 ]; then PROJECT_DIR_ARG="$arg"; NEXT_IS_PROJECT_DIR=0; continue; fi

  case "$arg" in
    --output-dir=*) OUTPUT_DIR_ARG="${arg#--output-dir=}" ;;
    --output-dir) NEXT_IS_OUTPUT_DIR=1 ;;
    --project-dir=*) PROJECT_DIR_ARG="${arg#--project-dir=}" ;;
    --project-dir) NEXT_IS_PROJECT_DIR=1 ;;
  esac
done

"$JAVA_BIN" -jar "$JAR_PATH" --project-dir="$PROJECT_DIR" --skip-text="$POS_SKIP_TEXT" --recompile="$POS_RECOMPILE" "$@"
RET="$?"

REPORT_FILE=""

if [ -n "${OUTPUT_DIR_ARG:-}" ]; then
  # ModulithExtractorMain writes the site under: <outputDir>/site/index.html
  if echo "$OUTPUT_DIR_ARG" | grep -q '^/'; then
    REPORT_FILE="$OUTPUT_DIR_ARG/site/index.html"
  else
    REPORT_FILE="$PROJECT_DIR/$OUTPUT_DIR_ARG/site/index.html"
  fi
elif [ -n "${PROJECT_DIR_ARG:-}" ]; then
  # In project mode (when --project-dir is set), default outputDir is:
  # <projectDir>/target/archimo-docs/site/index.html
  if echo "$PROJECT_DIR_ARG" | grep -q '^/'; then
    REPORT_FILE="$PROJECT_DIR_ARG/target/archimo-docs/site/index.html"
  else
    REPORT_FILE="$PROJECT_DIR/$PROJECT_DIR_ARG/target/archimo-docs/site/index.html"
  fi
else
  # Fallback: likely running from project root.
  REPORT_FILE="$PROJECT_DIR/target/archimo-docs/site/index.html"
fi

# Print a clickable file:// URL only if the report exists.
if [ -f "$REPORT_FILE" ]; then
  echo
  echo "Report: file://$REPORT_FILE"
else
  echo
  echo "Report not found: $REPORT_FILE"
fi

exit "$RET"

