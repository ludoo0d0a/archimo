#!/bin/sh

set -eu

# Simple installer/runner for Archimo.
# - Downloads the latest archimo.jar to the current directory
# - Optionally compiles the current Maven project (skip tests)
# - Uses JAVA_HOME if set, otherwise falls back to java on PATH

VERSION=1.2.0

JAR_NAME="${ARCHIMO_JAR:-archimo.jar}"
RELEASE_URL="${ARCHIMO_URL:-https://github.com/ludoo0d0a/archimo/releases/download/v$VERSION/archimo-release.jar}"

SCRIPT_DIR="$(cd "$(dirname "$0")" >/dev/null 2>&1 && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." >/dev/null 2>&1 && pwd)"

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

# If there is a Maven project here, build it like ModulithExtractorMain does
if [ -f "$PROJECT_DIR/pom.xml" ]; then
  MVN_BIN="${MAVEN_HOME:+$MAVEN_HOME/bin/mvn}"

  # Prefer a real `mvn` if available; otherwise fall back to Maven Wrapper in project root (`../mvnw`).
  if [ -n "${MVN_BIN:-}" ] && [ -x "$MVN_BIN" ]; then
    MVN_RUNNER="$MVN_BIN"
    MVN_IS_WRAPPER=0
  elif command -v mvn >/dev/null 2>&1; then
    MVN_RUNNER="mvn"
    MVN_IS_WRAPPER=0
  elif [ -f "$PROJECT_DIR/mvnw" ]; then
    MVN_RUNNER="$PROJECT_DIR/mvnw"
    MVN_IS_WRAPPER=1
  else
    echo "ERROR: Maven not found (`mvn`) and Maven Wrapper not present (`$PROJECT_DIR/mvnw`)." >&2
    exit 1
  fi

  echo "Detected pom.xml. Running: mvn compile dependency:copy-dependencies -DincludeScope=compile"
  if [ "$MVN_IS_WRAPPER" -eq 1 ]; then
    (cd "$PROJECT_DIR" && if [ -x "$MVN_RUNNER" ]; then "$MVN_RUNNER" -q compile dependency:copy-dependencies -DincludeScope=compile; else sh "$MVN_RUNNER" -q compile dependency:copy-dependencies -DincludeScope=compile; fi)
  else
    (cd "$PROJECT_DIR" && "$MVN_RUNNER" -q compile dependency:copy-dependencies -DincludeScope=compile)
  fi
fi

JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/java}"
if [ -z "${JAVA_BIN:-}" ]; then
  JAVA_BIN="java"
fi

exec "$JAVA_BIN" -jar "$JAR_NAME" "$@"

