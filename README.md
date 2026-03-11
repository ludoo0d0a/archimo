# Modulith Extractor

Java 17 CLI tool to parse a **Maven multi-module** project using **Spring Modulith** and extract:

- **C4 diagrams** (PlantUML) – all modules and per-module component diagrams  
- **Module canvases** (Asciidoc) – beans, aggregates, events, config per module  
- **Event map** – which modules publish/listen to which internal events  
- **Flows & sequences** – event flows and Mermaid sequence diagrams  

## Build

```bash
mvn package
```

Produces:

- `target/modulith-extractor-1.0.0-SNAPSHOT-all.jar` – runnable fat JAR (use this for CLI)

## Usage

### 1. Project mode (recommended)

Point at the root of your Maven/Spring Modulith application. The tool will run `mvn compile dependency:copy-dependencies` if needed, then extract.

```bash
java -jar target/modulith-extractor-1.0.0-SNAPSHOT-all.jar \
  --project-dir=/path/to/your/spring-modulith-app \
  --output-dir=./docs
```

Optional: `--app-class=com.example.YourApplication` if the main class is not in `pom.xml` (e.g. under `spring-boot-maven-plugin`).

### 2. Classpath mode

Use when the project is already built and you want to run with an explicit classpath (e.g. from the app’s root):

```bash
# From your Spring Modulith project root (after mvn package)
java -cp "target/classes:target/dependency/*:path/to/modulith-extractor-1.0.0-SNAPSHOT-all.jar" \
  com.archi.modulith.extract.ModulithExtractorMain \
  --app-class=com.example.YourApplication \
  --output-dir=./docs
```

Or with base package instead of main class:

```bash
java -cp "target/classes:target/dependency/*:path/to/modulith-extractor-*-all.jar" \
  com.archi.modulith.extract.ModulithExtractorMain \
  --base-package=com.example \
  --output-dir=./docs
```

## Output layout

- **C4 / PlantUML**: `*.puml` in the output directory (all-modules + per-module diagrams)  
- **Module canvases**: `*.adoc` (Asciidoc tables per module)  
- **JSON**: `json/events-map.json`, `json/event-flows.json`, `json/sequences.json`, `json/module-dependencies.json`, `json/extract-result.json`  
- **Mermaid**: `mermaid/event-flows.mmd`, `mermaid/sequence-*.mmd`, `mermaid/module-dependencies.mmd`  

## Requirements

- Java 17+  
- Target project: Maven, Spring Modulith, with `spring-modulith-core` / `spring-modulith-docs` on the classpath when running the extractor (handled automatically in project mode)  

## CI: Build JAR with GitHub Actions

The workflow in `.github/workflows/build.yml` builds the project and uploads the fat JAR as an artifact:

- **Trigger**: push / PR to `main` or `master`  
- **Artifact**: `modulith-extractor-jar` → `modulith-extractor-*-all.jar`  

Download the artifact from the Actions run to use the JAR elsewhere.
