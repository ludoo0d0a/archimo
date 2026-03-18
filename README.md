# Archimo

[![Live Report](https://img.shields.io/badge/Live%20Report-View-brightgreen?logo=googlechrome&logoColor=white)](https://ludoo0d0a.github.io/archimo/)

**Repository:** [https://github.com/ludoo0d0a/archimo](https://github.com/ludoo0d0a/archimo)

Java 17 CLI and test integration to parse **Spring Modulith** applications and extract:

- **C4 diagrams (PlantUML)** – all modules and per-module component diagrams  
- **Module canvases (Asciidoc)** – beans, aggregates, events, config per module  
- **Event map** – which modules publish/listen to which internal events  
- **Flows & sequences** – event flows and **Mermaid** sequence/dependency diagrams  
- **Website report** – static HTML report to browse diagrams and search modules/classes/events (JaCoCo-style)

## Quick start

```bash
mvn package
java -jar target/archimo-1.0.0-SNAPSHOT-all.jar --project-dir=/path/to/your/modulith-app --output-dir=./docs
```

Or, from any directory, **download and run the latest `archimo.jar` from GitHub** in one line:

```bash
wget -qO- https://raw.githubusercontent.com/ludoo0d0a/archimo/main/scripts/archimo.sh | sh
```

Or run the extractor **from your tests** so every `mvn test` produces the same outputs:

```java
@ExtendWith(ArchimoReportExtension.class)
@ArchimoReport(MyApplication.class)
class MyApplicationTests { ... }
```

**Full usage (executable JAR, classpath mode, test integration, CI/CD):** see **[USAGE.md](USAGE.md)**.

## Output layout

- **C4 / PlantUML**: `*.puml` in the output directory  
- **Mermaid**: `mermaid/*.mmd` (event flows, sequences, module dependencies)  
- **JSON**: `json/events-map.json`, `event-flows.json`, `sequences.json`, `module-dependencies.json`  
- **Website report**: `site/` (index.html + site-index.json for navigation and search)

## CI/CD

The workflow in `.github/workflows/build.yml`:

- Builds and runs tests (report is generated from the sample app via `-Darchimo.generateReport=true`)
- Uploads **archimo-jar** and **modulith-docs** as artifacts
- **Publishes the HTML report to GitHub Pages** on every push to `main`

**One-time repo setup:** **Settings → Pages → Build and deployment → Source: GitHub Actions.**  
See **[docs/GITHUB_PAGES_SETUP.md](docs/GITHUB_PAGES_SETUP.md)** for step-by-step instructions.

## Requirements

- Java 17+  
- Target: Maven, Spring Modulith (`spring-modulith-core` / `spring-modulith-docs` on classpath; project mode runs Maven for you)
