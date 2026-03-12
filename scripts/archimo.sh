#!/bin/sh

set -eu

# Simple installer/runner for Archimo.
# - Downloads the latest archimo.jar to the current directory
# - Optionally compiles the current Maven project (skip tests)
# - Uses JAVA_HOME if set, otherwise falls back to java on PATH

JAR_NAME="${ARCHIMO_JAR:-archimo.jar}"
RELEASE_URL="${ARCHIMO_URL:-https://github.com/ludoo0d0a/archimo/releases/latest/download/archimo.jar}"

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
if [ -f "pom.xml" ]; then
  MVN_BIN="${MAVEN_HOME:+$MAVEN_HOME/bin/mvn}"
  if [ -z "${MVN_BIN:-}" ]; then
    MVN_BIN="mvn"
  fi

  echo "Detected pom.xml. Running: mvn compile dependency:copy-dependencies -DincludeScope=compile"
  "$MVN_BIN" -q compile dependency:copy-dependencies -DincludeScope=compile
fi

JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/java}"
if [ -z "${JAVA_BIN:-}" ]; then
  JAVA_BIN="java"
fi

exec "$JAVA_BIN" -jar "$JAR_NAME" "$@"

