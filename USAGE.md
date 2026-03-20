# Archimo – Usage

Archimo extracts **C4 (PlantUML)**, **Mermaid** diagrams and a **static website report** from Spring Modulith applications. You can run it as an **executable JAR** (e.g. in CI) or **from your tests** so reports are produced automatically.

---

## 1. Build

```bash
mvn package -DskipTests
```

Produces:

- **`target/archimo-<version>-all.jar`** – runnable fat JAR (use this for CLI and CI)
- **`target/archimo-<version>.jar`** – thin JAR (requires `lib/` from `target/lib` when using `java -jar` with `-cp`)

---

## 2. Run as executable JAR

Two modes: **project mode** (point at a Maven project; Archimo builds it if needed) and **classpath mode** (you provide classpath and main class or base package).

### 2.1 Project mode (recommended for CI)

Point at the root of your Maven/Spring Modulith application. The tool runs `mvn compile dependency:copy-dependencies` if needed, then extracts.

```bash
java -jar target/archimo-1.0.0-SNAPSHOT-all.jar \
  --project-dir=/path/to/your/spring-modulith-app \
  --output-dir=./docs
```

Optional:

- **`--app-class=com.example.YourApplication`** – use if the main class is not declared under `spring-boot-maven-plugin` in `pom.xml`.
- **`--output-dir=<path>`** – default is `<project-dir>/target/archimo-docs`.
- **`-o` / `--output-format`** – which diagram/export writers run. Comma-separated: **`plantuml`**, **`mermaid`**, **`json`**. Default is **`plantuml,mermaid`**. The **`json`** format writes **`architecture.json`** at the report root (same JSON model as **`archimo.mf`**; also mirrored under `json/c4-report-tree.json`).
- **`archimo.mf`** (optional) – JSON manifest at the **project root** (same shape as `architecture.json`, can be partial). When present, Archimo **starts** the C4 report tree from it and **merges** scan results; overlapping element ids with different label/kind/technology/attributes emit **warnings** on stderr.
- **`--messaging-scan-concurrency=auto|virtual|platform`** – controls how **MessagingScanner** parallelizes work over application classes (listeners + template call detection):
  - **`auto`** (default) – use virtual threads when the JVM supports them (**Java 21+**), otherwise a bounded platform thread pool.
  - **`virtual`** – same preference as `auto`; on older JDKs without virtual threads, falls back to the platform pool.
  - **`platform`** – never use virtual threads; always use a bounded platform thread pool (useful for benchmarking or restrictive environments).

Other JVM-related flags (passed to the **child** JVM in project / GitHub mode):

- **`--xmx=<size>`**, **`--xms=<size>`**, **`--xss=<size>`** – heap and stack for the extraction process (see `java -jar … --help` style usage via `ArchimoMain`).

Example in CI (e.g. GitHub Actions):

```yaml
- run: mvn -B package -DskipTests
- run: java -jar target/archimo-*-all.jar --project-dir=${{ github.workspace }} --output-dir=${{ github.workspace }}/target/archimo-docs
- uses: actions/upload-artifact@v4
  with:
    name: archimo-docs
    path: target/archimo-docs/
```

### 2.2 Classpath mode

Use when the project is already built and you want to run with an explicit classpath (e.g. from the app’s root):

```bash
# From your Spring Modulith project root (after mvn package)
java -cp "target/classes:target/dependency/*:path/to/archimo-1.0.0-SNAPSHOT-all.jar" \
  fr.geoking.archimo.extract.ArchimoMain \
  --app-class=com.example.YourApplication \
  --output-dir=./docs \
  --messaging-scan-concurrency=virtual
```

Or with **base package** instead of main class:

```bash
java -cp "target/classes:target/dependency/*:path/to/archimo-*-all.jar" \
  fr.geoking.archimo.extract.ArchimoMain \
  --base-package=com.example \
  --output-dir=./docs
```

---

## 3. Include in tests

You can run the extractor **during your test phase** so that every `mvn test` (or CI test run) produces the same outputs (PlantUML, Mermaid, JSON, website). Two options: **JUnit 5 extension** or **one-off test + system property**.

### 3.1 JUnit 5 extension (recommended)

Add Archimo as a **test** dependency and attach the report extension to a test class. The report is generated **after all tests** in that class.

**1. Add dependency (test scope):**

```xml
<dependency>
  <groupId>fr.geoking.archimo</groupId>
  <artifactId>archimo</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <scope>test</scope>
</dependency>
```

**2. Use the extension and annotation:**

```java
import fr.geoking.archimo.report.ArchimoReport;
import fr.geoking.archimo.report.ArchimoReportExtension;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(ArchimoReportExtension.class)
@ArchimoReport(mainClass = MyApplication.class)  // or @ArchimoReport(MyApplication.class)
class MyApplicationTests {
    // your tests; after they finish, report is written to target/archimo-docs
}
```

Or configure via **system properties** (no annotation):

- **`archimo.appClass`** – fully qualified main class (e.g. `com.example.MyApplication`).
- **`archimo.report.outputDir`** – output directory (default: `target/archimo-docs`).
- **`archimo.messagingScanConcurrency`** – same values as CLI: `auto`, `virtual`, or `platform` (default: `auto`). Applies when you use `ModulithExtractor` with bytecode scanning; the JUnit extension does not set a project directory by default, so class-path scanners may be skipped unless you invoke the extractor with a `projectDir` yourself.

```bash
mvn test -Darchimo.appClass=com.example.MyApplication
mvn test -Darchimo.messagingScanConcurrency=platform
```

### 3.2 Generate report only in CI

To generate the report only in CI (so local `mvn test` stays fast), set system properties in your CI script. The extension will still run after the test class; use a dedicated test class that runs only when the property is set:

```java
@ExtendWith(ArchimoReportExtension.class)
@ArchimoReport(MyApplication.class)
@EnabledIfSystemProperty(named = "archimo.generateReport", matches = "true")
class ArchitectureReportTest {
    @Test
    void placeholderSoClassRuns() { /* report is generated in afterAll */ }
}
```

Then in CI:

```bash
mvn test -Darchimo.generateReport=true
```

and archive `target/archimo-docs/` as an artifact (or publish the `site/` folder to GitHub Pages).

### 3.3 Call the extractor from a test (no extension)

You can also call `ModulithExtractor` directly in a test and write to a fixed directory:

```java
import fr.geoking.archimo.extract.MessagingScanConcurrency;
import fr.geoking.archimo.extract.ModulithExtractor;
import org.springframework.modulith.core.ApplicationModules;

@Test
void generateArchitectureReport() throws Exception {
    Path outputDir = Path.of("target/archimo-docs");
    Path projectDir = Path.of("."); // module root: enables ArchUnit scanners (endpoints, messaging, …)
    ApplicationModules modules = ApplicationModules.of(MyApplication.class);
    ModulithExtractor extractor = new ModulithExtractor(
            modules, outputDir, projectDir, false,
            MessagingScanConcurrency.VIRTUAL);
    extractor.extract();
}
```

---

## 4. Output layout

All paths are relative to `--output-dir` (or `target/archimo-docs` when using the extension).

| Output | Path | Description |
|--------|------|-------------|
| **PlantUML (C4)** | `*.puml` | All-modules and per-module C4 diagrams |
| **Module canvases** | `*.adoc` | Asciidoc tables per module (beans, events, config) |
| **Mermaid** | `mermaid/*.mmd` | Event flows, sequences, module dependencies |
| **JSON** | `json/*.json` | `events-map.json`, `event-flows.json`, `sequences.json`, `module-dependencies.json`, `extract-result.json` |
| **Website report** | `site/` | Static HTML/CSS/JS report: browse C4 diagrams, search modules/classes/events |

The **website** (`site/index.html`) is a single-page app that loads `site-index.json` and lets you filter diagrams and search modules, classes and events.

---

## 5. CI/CD examples

### 5.1 GitHub Actions: JAR + report artifact

```yaml
- run: mvn -B package -Darchimo.generateReport=true   # if report is generated by a test
# or:
- run: java -jar target/archimo-*-all.jar --project-dir=. --output-dir=target/archimo-docs

- uses: actions/upload-artifact@v4
  with:
    name: archimo-docs
    path: target/archimo-docs/
```

### 5.2 GitHub Actions: Publish site to GitHub Pages

This project’s workflow (`.github/workflows/build.yml`) publishes the report on every push to `main` using the official Pages actions:

```yaml
permissions:
  contents: read
  pages: write
  id-token: write

jobs:
  build:
    # ... build and generate report to target/archimo-docs ...
    - uses: actions/upload-pages-artifact@v3
      with:
      path: target/archimo-docs/site

  deploy:
    if: github.event_name == 'push' && github.ref == 'refs/heads/main'
    needs: build
    runs-on: ubuntu-latest
    environment: github_pages
    steps:
      - uses: actions/deploy-pages@v4
```

Enable **Settings → Pages → Source: GitHub Actions** in your repo. Alternative (push to `gh-pages` branch):

```yaml
- name: Deploy site to GitHub Pages
  uses: peaceiris/actions-gh-pages@v4
  with:
    github_token: ${{ secrets.GITHUB_TOKEN }}
    publish_dir: target/archimo-docs/site
```

### 5.3 Consuming project (your app): test + report

In **your** Spring Modulith app’s `pom.xml`:

```xml
<dependency>
  <groupId>fr.geoking.archimo</groupId>
  <artifactId>archimo</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <scope>test</scope>
</dependency>
```

Then add a test class with `@ExtendWith(ArchimoReportExtension.class)` and `@ArchimoReport(YourApplication.class)`. On `mvn test`, the report is written to `target/archimo-docs`. In CI, add a step to upload `target/archimo-docs` as an artifact (and optionally publish `site/` to Pages).

---

## 6. Requirements

- **Java 25+**
- Target project: **Maven**, **Spring Modulith**, with `spring-modulith-core` / `spring-modulith-docs` on the classpath when running the extractor (handled automatically in project mode).

---

## 7. Summary

| Use case | Command / setup |
|----------|------------------|
| **CLI (another project)** | `java -jar archimo-*-all.jar --project-dir=/path/to/app [--output-dir=...] [--messaging-scan-concurrency=…]` |
| **CLI (classpath)** | `java -cp "..." fr.geoking.archimo.extract.ArchimoMain --app-class=... [--output-dir=...] [--messaging-scan-concurrency=...]` |
| **Report from tests** | Add archimo test dependency + `@ExtendWith(ArchimoReportExtension.class)` and `@ArchimoReport(YourApp.class)` |
| **Report only in CI** | `mvn test -Darchimo.generateReport=true` (and optionally `-Darchimo.appClass=...`) then archive `target/archimo-docs` |

Outputs are always: **PlantUML**, **Mermaid**, **JSON** and **website report** under the chosen output directory.
