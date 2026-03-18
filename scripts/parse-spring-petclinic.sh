#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

# Clone destination (local checkout)
PETCLINIC_DIR="${PETCLINIC_DIR:-./target/spring-petclinic}"
# Where Archimo writes the generated report
REPORT_DIR="${REPORT_DIR:-./target/petclinic-archimo-docs}"

# Spring Petclinic main class (Spring Boot)
PETCLINIC_APP_CLASS="${PETCLINIC_APP_CLASS:-org.springframework.samples.petclinic.PetClinicApplication}"

# Git clone parameters
PETCLINIC_REPO_URL="${PETCLINIC_REPO_URL:-https://github.com/spring-projects/spring-petclinic.git}"
PETCLINIC_CLONE_DEPTH="${PETCLINIC_CLONE_DEPTH:-1}"

rm -rf "$PETCLINIC_DIR" "$REPORT_DIR"

git clone --depth "$PETCLINIC_CLONE_DEPTH" "$PETCLINIC_REPO_URL" "$PETCLINIC_DIR"

# Runs Archimo as a downloaded executable JAR (see scripts/archimo.sh)
./scripts/archimo.sh \
  --project-dir="$PETCLINIC_DIR" \
  --app-class="$PETCLINIC_APP_CLASS" \
  --output-dir="$REPORT_DIR"

