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

if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo "ERROR: Current directory is not a git repository." >&2
  exit 1
fi

if ! git diff --quiet || ! git diff --cached --quiet; then
  echo "ERROR: You have uncommitted changes. Please commit or stash them first." >&2
  exit 1
fi

echo "Fetching latest refs from $REMOTE_NAME..."
git fetch "$REMOTE_NAME" --tags

echo "Checking out main branch: $MAIN_BRANCH"
git checkout "$MAIN_BRANCH"
git pull --ff-only "$REMOTE_NAME" "$MAIN_BRANCH"

echo "Determining next minor version from existing tags..."
LATEST_TAG="$(git tag --list 'v*.*.*' --sort=-v:refname | head -n 1 || true)"

if [ -z "$LATEST_TAG" ]; then
  echo "No existing semantic version tags found. Starting at v1.0.0."
  NEW_TAG="v1.0.0"
else
  BASE="${LATEST_TAG#v}"
  IFS='.' read -r MAJOR MINOR PATCH <<< "$BASE"
  MINOR=$((MINOR + 1))
  PATCH=0
  NEW_TAG="v${MAJOR}.${MINOR}.${PATCH}"
fi

echo "Creating and pushing new tag: $NEW_TAG"
git tag -a "$NEW_TAG" -m "Release $NEW_TAG"
git push "$REMOTE_NAME" "$MAIN_BRANCH"
git push "$REMOTE_NAME" "$NEW_TAG"

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

git merge --no-ff "$MAIN_BRANCH" -m "chore: merge $MAIN_BRANCH into $RELEASE_BRANCH for $NEW_TAG"
git push "$REMOTE_NAME" "$RELEASE_BRANCH"

echo "Done. Branch '$RELEASE_BRANCH' now contains '$MAIN_BRANCH' at $NEW_TAG."
echo "Any workflows listening to pushes on '$RELEASE_BRANCH' or tag '$NEW_TAG' should now be triggered."

