#!/usr/bin/env bash

set -euo pipefail

# Configuration (override via env vars if needed)
MAIN_BRANCH="${MAIN_BRANCH:-main}"
RELEASE_BRANCH="${RELEASE_BRANCH:-release}"
REMOTE_NAME="${REMOTE_NAME:-origin}"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

echo "Using remote='$REMOTE_NAME', main='$MAIN_BRANCH', release='$RELEASE_BRANCH'"

if ! command -v git >/dev/null 2>&1; then
  echo "ERROR: git is not installed or not in PATH." >&2
  exit 1
fi

if [ ! -f "pom.xml" ]; then
  echo "ERROR: pom.xml not found at repo root ($ROOT_DIR)." >&2
  exit 1
fi

if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo "ERROR: Current directory is not a git repository." >&2
  exit 1
fi

if ! git diff --quiet || ! git diff --cached --quiet; then
  echo "ERROR: You have uncommitted changes. Please commit or stash them first." >&2
  exit 1
fi

echo "Fetching latest refs from $REMOTE_NAME..."
git fetch "$REMOTE_NAME"

echo "Checking out main branch: $MAIN_BRANCH"
git checkout "$MAIN_BRANCH"
git pull --ff-only "$REMOTE_NAME" "$MAIN_BRANCH"

echo "Bumping minor version in root pom.xml..."
NEW_VERSION="$(
  python - << 'PY'
import re
from pathlib import Path

p = Path("pom.xml")
text = p.read_text(encoding="utf-8")

m = re.search(r"<version>(\d+)\.(\d+)\.(\d+)(-SNAPSHOT)?</version>", text)
if not m:
    raise SystemExit("Could not find a semantic version in pom.xml (expected <version>X.Y.Z[-SNAPSHOT]</version>).")

major, minor, patch = map(int, m.group(1, 2, 3))
minor += 1
patch = 0
new_version = f"{major}.{minor}.{patch}-SNAPSHOT"

old_tag = m.group(0)
new_tag = f"<version>{new_version}</version>"

new_text = text.replace(old_tag, new_tag, 1)
p.write_text(new_text, encoding="utf-8")

print(new_version)
PY
)"

echo "New version: $NEW_VERSION"

if git diff --quiet; then
  echo "No changes detected in pom.xml after version bump. Aborting."
  exit 1
fi

git commit -am "chore: bump version to $NEW_VERSION"
git push "$REMOTE_NAME" "$MAIN_BRANCH"

echo "Syncing release branch: $RELEASE_BRANCH with $MAIN_BRANCH..."
if git show-ref --verify --quiet "refs/heads/$RELEASE_BRANCH"; then
  git checkout "$RELEASE_BRANCH"
else
  if git ls-remote --exit-code --heads "$REMOTE_NAME" "$RELEASE_BRANCH" >/dev/null 2>&1; then
    git checkout -b "$RELEASE_BRANCH" "$REMOTE_NAME/$RELEASE_BRANCH"
  else
    git checkout -B "$RELEASE_BRANCH" "$MAIN_BRANCH"
  fi
fi

git merge --no-ff "$MAIN_BRANCH" -m "chore: merge $MAIN_BRANCH into $RELEASE_BRANCH for v$NEW_VERSION"
git push "$REMOTE_NAME" "$RELEASE_BRANCH"

echo "Done. Branch '$RELEASE_BRANCH' now contains '$MAIN_BRANCH' with version $NEW_VERSION."
echo "Any workflows listening to pushes on '$RELEASE_BRANCH' should now be triggered."

