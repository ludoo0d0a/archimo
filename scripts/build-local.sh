#!/bin/sh
set -eu

# Build the project locally using the Maven Wrapper.
# Optional env vars:
# - SKIP_TESTS=1                 : pass -DskipTests
# - ARCHIMO_GENERATE_REPORT=1   : pass -Darchimo.generateReport=true (requires tests to run)
# - MAVEN_QUIET=1               : add -q to Maven for less log output
# - MVN_MODULE_ARGS="-pl archimo -am" : Maven module selection args (default: build `archimo` and deps)
# - MVN_GOALS="package"              : Maven goals/phases (default: package)

ROOT_DIR="$(cd "$(dirname "$0")/.." >/dev/null 2>&1 && pwd)"
cd "$ROOT_DIR"

MVNW="./mvnw"
if [ ! -f "$MVNW" ]; then
  echo "ERROR: Maven wrapper not found at: $MVNW" >&2
  exit 1
fi

MVN_MODULE_ARGS="${MVN_MODULE_ARGS:--pl archimo -am}"
MVN_GOALS="${MVN_GOALS:-package}"
MVN_ARGS="-B --no-transfer-progress"

if [ "${SKIP_TESTS:-0}" = "1" ]; then
  MVN_ARGS="$MVN_ARGS -DskipTests"
fi

if [ "${ARCHIMO_GENERATE_REPORT:-0}" = "1" ]; then
  MVN_ARGS="$MVN_ARGS -Darchimo.generateReport=true"
fi

MAVEN_QUIET="${MAVEN_QUIET:-1}"
if [ "$MAVEN_QUIET" = "1" ]; then
  MVN_ARGS="$MVN_ARGS -q"
fi

"$MVNW" $MVN_ARGS $MVN_MODULE_ARGS "$MVN_GOALS" "$@"
RET="$?"

if [ "$RET" -ne 0 ]; then
  exit "$RET"
fi

# Print jar paths for convenience (so you can copy/click them).
FAT_JAR=""
for f in "$ROOT_DIR"/archimo/target/archimo-*-all.jar; do
  if [ -f "$f" ]; then
    FAT_JAR="$f"
    break
  fi
done

THIN_JAR=""
for f in "$ROOT_DIR"/archimo/target/archimo-*.jar; do
  if [ -f "$f" ]; then
    case "$f" in
      *-all.jar) continue ;;
      *) THIN_JAR="$f"; break ;;
    esac
  fi
done

echo
if [ -n "$FAT_JAR" ]; then
  echo "Built fat jar: $FAT_JAR"
else
  echo "Built fat jar: not found under archimo/target"
fi

if [ -n "$THIN_JAR" ]; then
  echo "Built thin jar: $THIN_JAR"
else
  echo "Built thin jar: not found under archimo/target"
fi

exit 0

