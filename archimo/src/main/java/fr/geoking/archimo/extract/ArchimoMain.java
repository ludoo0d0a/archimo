package fr.geoking.archimo.extract;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import fr.geoking.archimo.extract.model.ExtractResult;
import fr.geoking.archimo.extract.output.OutputFormat;
import org.springframework.modulith.core.ApplicationModules;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * CLI entry point for the Archimo Modulith Extractor.
 */
public final class ArchimoMain {

    private static Logger logger = Logger.getInstance();

    public static void main(String[] args) {
        Config config = Config.parse(args);
        if (config == null) {
            printUsage();
            System.exit(1);
        }

        logger = new Logger(config.verbose, config.logFile);
        Logger.setInstance(logger);

        String version = ArchimoMain.class.getPackage().getImplementationVersion();
        logger.success("Archimo " + (version != null ? version : "development"));

        if (config.githubUrl != null) {
            runGithubMode(config);
            return;
        }

        if (config.generateWorkflow) {
            generateWorkflow();
            return;
        }

        if (config.projectDir != null && !config.internalChild) {
            runProjectMode(config);
            return;
        }

        runExtraction(config);
    }

    private static void runGithubMode(Config config) {
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("archimo-gh-");
            Path finalTempDir = tempDir;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    if (Files.exists(finalTempDir)) {
                        try (var walk = Files.walk(finalTempDir)) {
                            walk.sorted(Comparator.reverseOrder())
                                    .map(Path::toFile)
                                    .forEach(File::delete);
                        }
                    }
                } catch (IOException ignored) {
                }
            }));

            logger.info("Cloning " + config.githubUrl + " into " + tempDir + "...");
            logger.indent();

            ProcessBuilder pb = new ProcessBuilder("git", "clone", "--depth", "1", "--", config.githubUrl, ".");
            pb.directory(tempDir.toFile());
            pb.inheritIO();
            int exit = pb.start().waitFor();
            if (exit != 0) {
                logger.error("Git clone failed.");
                System.exit(1);
            }

            Config newConfig = new Config(tempDir.toFile(), config.appClass, config.basePackage, config.outputDir, null, false, config.fullDependencyMode, config.module, config.serve, config.verbose, config.logFile, config.xmx, config.xms, config.xss, config.messagingScanConcurrency, false, config.outputFormats);
            runProjectMode(newConfig);
            logger.unindent();

        } catch (Exception e) {
            logger.error("Github mode failed", e);
            System.exit(1);
        }
    }

    private static void runProjectMode(Config config) {
        Path outDir = null;
        try {
            Path projectDir = config.projectDir.toPath();
            logger.info("Project: " + projectDir.toAbsolutePath());
            logger.indent();
            String appClass = config.appClass;
            if (appClass == null) {
                appClass = MainClassDiscovery.discover(projectDir);
                if (appClass == null) {
                    // Try monorepo discovery
                    logger.debug("Main class not found in root. Searching in sub-modules...");
                    try (var stream = Files.list(projectDir)) {
                        List<Path> subDirs = stream.filter(Files::isDirectory).toList();
                        if (config.module != null) {
                            Path moduleDir = projectDir.resolve(config.module);
                            if (Files.isDirectory(moduleDir) && Files.exists(moduleDir.resolve("pom.xml"))) {
                                appClass = MainClassDiscovery.discover(moduleDir);
                                if (appClass != null) {
                                    projectDir = moduleDir;
                                    logger.info("Module: " + config.module);
                                }
                            }
                        } else {
                            for (Path subDir : subDirs) {
                                if (Files.exists(subDir.resolve("pom.xml"))) {
                                    appClass = MainClassDiscovery.discover(subDir);
                                    if (appClass != null) {
                                        projectDir = subDir;
                                        logger.info("Module: " + subDir.getFileName());
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
                if (appClass == null) {
                    logger.error("Could not discover main application class. Use --app-class=fully.qualified.MainClass or --module=<name>");
                    System.exit(1);
                }
                logger.debug("Discovered application class: " + appClass);
            }

            Path target = projectDir.resolve("target");
            Path classes = target.resolve("classes");
            Path dependencyDir = target.resolve("dependency");

            boolean classesMissing = !java.nio.file.Files.isDirectory(classes);
            boolean depsMissing = !java.nio.file.Files.isDirectory(dependencyDir);

            if (classesMissing || depsMissing) {
                if (classesMissing && depsMissing) {
                    logger.warn("Project artifacts not found (classes and dependencies).");
                } else if (classesMissing) {
                    logger.warn("Project classes not found in " + classes.toAbsolutePath());
                } else {
                    logger.warn("Project dependencies not found in " + dependencyDir.toAbsolutePath());
                }

                logger.info("Building project...");
                logger.indent();
                logger.debug("Command: mvn -f " + projectDir.toAbsolutePath() + " compile dependency:copy-dependencies -DincludeScope=compile -DskipTests");
                int exit = runMaven(projectDir, "-f", projectDir.toAbsolutePath().toString(), "compile", "dependency:copy-dependencies", "-DincludeScope=compile", "-DskipTests");
                logger.unindent();
                if (exit != 0) {
                    logger.error("Maven build failed.");
                    System.exit(1);
                }
                logger.success("Project built successfully.");
            }

            String depJars;
            try (var list = Files.list(dependencyDir)) {
                depJars = list.filter(p -> p.toString().endsWith(".jar"))
                        .map(Path::toAbsolutePath)
                        .map(Path::toString)
                        .collect(Collectors.joining(java.io.File.pathSeparator));
            }
            String cp = classes.toAbsolutePath() + java.io.File.pathSeparator + depJars + java.io.File.pathSeparator + getCurrentJarPath();

            outDir = config.outputDir != null ? config.outputDir.toPath() : target.resolve("archimo-docs");
            String outDirStr = outDir.toAbsolutePath().toString();

            // Use a Java argument file to avoid very long command lines on Windows (CreateProcess error=206)
            List<String> javaArgs = new ArrayList<>();
            javaArgs.add("-cp");
            javaArgs.add(cp);
            javaArgs.add(ArchimoMain.class.getName());
            javaArgs.add("--app-class=" + appClass);
            javaArgs.add("--output-dir=" + outDirStr);
            javaArgs.add("--project-dir=" + config.projectDir.getAbsolutePath());
            if (config.fullDependencyMode) {
                javaArgs.add("--full-dependency-mode");
            }
            if (config.verbose) {
                javaArgs.add("--verbose");
            }
            if (config.logFile != null) {
                javaArgs.add("--log-file=" + config.logFile.getAbsolutePath());
            }
            if (config.messagingScanConcurrency != MessagingScanConcurrency.AUTO) {
                javaArgs.add("--messaging-scan-concurrency=" + config.messagingScanConcurrency.name().toLowerCase());
            }
            if (config.outputFormats != null && !config.outputFormats.isEmpty()) {
                javaArgs.add("--output-format=" + config.outputFormats.stream()
                        .map(Enum::name)
                        .map(String::toLowerCase)
                        .collect(Collectors.joining(",")));
            }
            javaArgs.add("--internal-child");

            Path argsFile = Files.createTempFile(target, "archimo-java-args-", ".txt");
            Files.write(argsFile, javaArgs);

            List<String> cmd = new ArrayList<>();
            cmd.add(getJavaExecutable());
            if (config.xmx != null) {
                cmd.add("-Xmx" + config.xmx);
            } else {
                cmd.add("-Xmx1536m");
            }
            if (config.xms != null) cmd.add("-Xms" + config.xms);
            if (config.xss != null) cmd.add("-Xss" + config.xss);
            cmd.add("@" + argsFile.toAbsolutePath());

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.inheritIO();
            pb.directory(projectDir.toFile());
            int exit = pb.start().waitFor();
            logger.unindent();
            if (exit == 0 && config.serve && outDir != null) {
                startWebServer(outDir.resolve("site"));
            }
            System.exit(exit);
        } catch (Exception e) {
            logger.error("Project mode failed", e);
            System.exit(1);
        }
    }

    private static String getCurrentJarPath() {
        String path = ArchimoMain.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        if (path != null && path.endsWith(".jar")) return path;
        return path;
    }

    private static String getJavaExecutable() {
        String javaHome = System.getProperty("java.home");
        return Path.of(javaHome).resolve("bin").resolve("java").toString();
    }

    private static int runMaven(Path projectDir, String... goals) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(getMavenCommand(projectDir));
        for (String g : goals) cmd.add(g);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(projectDir.toFile());
        pb.inheritIO();
        return pb.start().waitFor();
    }

    private static String getMavenCommand(Path projectDir) {
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        Path wrapper = projectDir.resolve(windows ? "mvnw.cmd" : "mvnw");
        if (java.nio.file.Files.isRegularFile(wrapper)) {
            return wrapper.toAbsolutePath().toString();
        }
        return windows ? "mvn.cmd" : "mvn";
    }

    private static void runExtraction(Config config) {
        try {
            ApplicationModules modules = null;
            try {
                if (config.basePackage != null) {
                    modules = ApplicationModules.of(config.basePackage);
                } else if (config.appClass != null) {
                    Class<?> app = Class.forName(config.appClass);
                    modules = ApplicationModules.of(app);
                }
            } catch (Exception e) {
                logger.debug("Could not initialize Spring Modulith modules (ignoring if not a Modulith project): " + e.getMessage());
            }

            Path outputDir = config.outputDir != null ? config.outputDir.toPath() : Path.of("archimo-docs");
            Path projectDir = config.projectDir != null ? config.projectDir.toPath() : null;
            ModulithExtractor extractor = new ModulithExtractor(modules, outputDir, projectDir, config.fullDependencyMode,
                    config.messagingScanConcurrency, config.appClass, config.outputFormats);
            ExtractResult result = extractor.extract();

            logger.success("Extraction complete! 🚀");
            logger.info("Output: " + outputDir.toAbsolutePath());
            logger.indent();
            logger.debug("C4/PlantUML: " + outputDir.toAbsolutePath() + File.separator + "*.puml");
            logger.debug("Module canvases: " + outputDir.toAbsolutePath() + File.separator + "*.adoc");
            logger.debug("Events map & flows: " + outputDir.resolve("json"));
            logger.debug("Mermaid: " + outputDir.resolve("mermaid"));
            logger.unindent();

            writeGitHubSummary(result, outputDir);
            if (config.serve && !config.internalChild) {
                startWebServer(outputDir.resolve("site"));
            }
        } catch (Exception e) {
            logger.error("Extraction failed", e);
            System.exit(1);
        }
    }

    private static void writeGitHubSummary(ExtractResult result, Path outputDir) {
        String summaryFile = System.getenv("GITHUB_STEP_SUMMARY");
        if (summaryFile == null || summaryFile.isBlank()) {
            return;
        }

        try {
            StringBuilder sb = new StringBuilder();
            sb.append("# Archimo Extraction Summary\n\n");
            sb.append("Extraction complete! Here are some stats:\n\n");
            sb.append("- **Modules**: ").append(result.eventsMap().size()).append("\n");
            sb.append("- **Events**: ").append(result.flows().size()).append("\n");
            sb.append("- **Command Flows**: ").append(result.commandFlows().size()).append("\n");
            sb.append("- **Messaging Flows**: ").append(result.messagingFlows().size()).append("\n");
            sb.append("- **BPMN Flows**: ").append(result.bpmnFlows().size()).append("\n");
            sb.append("- **Architecture Components**: ").append(result.architectureInfos().size()).append("\n");
            sb.append("- **OpenAPI / Swagger files**: ").append(result.openApiSpecFiles().size()).append("\n");
            sb.append("- **External HTTP client usages**: ").append(result.externalHttpClients().size()).append("\n");
            var infra = result.infrastructureTopology();
            sb.append("- **Docker/Kubernetes manifest files**: ").append(infra.files().size()).append("\n");
            sb.append("- **Inferred external systems (from manifests)**: ").append(infra.externalSystems().size()).append("\n\n");

            String serverUrl = System.getenv("GITHUB_SERVER_URL");
            String repo = System.getenv("GITHUB_REPOSITORY");
            String runId = System.getenv("GITHUB_RUN_ID");

            if (serverUrl != null && repo != null && runId != null) {
                String artifactsUrl = serverUrl + "/" + repo + "/actions/runs/" + runId + "#artifacts";
                sb.append("[View full report artifacts](").append(artifactsUrl).append(")\n");
            } else {
                sb.append("You can access the full report in the artifacts section.\n");
            }

            Files.writeString(Path.of(summaryFile), sb.toString(), java.nio.file.StandardOpenOption.APPEND);
            logger.success("GitHub Actions summary written.");
        } catch (IOException e) {
            logger.error("Failed to write GitHub Actions summary: " + e.getMessage());
        }
    }

    private static void startWebServer(Path siteDir) {
        if (!Files.isDirectory(siteDir)) {
            logger.error("Site directory not found: " + siteDir.toAbsolutePath());
            return;
        }
        try {
            int port = 8080;
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/", new StaticFileHandler(siteDir));
            server.setExecutor(Executors.newFixedThreadPool(2));
            logger.success("Web server started at http://localhost:" + port + " 🌐");
            logger.info("To run it manually: python3 -m http.server -d " + siteDir.toAbsolutePath() + " " + port);
            logger.info("Press Ctrl+C to stop.");
            server.start();

            // Keep alive
            Thread.currentThread().join();
        } catch (Exception e) {
            logger.error("Failed to start web server: " + e.getMessage());
        }
    }

    private static class StaticFileHandler implements HttpHandler {
        private final Path baseDir;

        StaticFileHandler(Path baseDir) {
            this.baseDir = baseDir;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/")) path = "/index.html";

            Path file = baseDir.resolve(path.substring(1));
            if (Files.exists(file) && !Files.isDirectory(file)) {
                String contentType = getContentType(file);
                byte[] content = Files.readAllBytes(file);
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.sendResponseHeaders(200, content.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(content);
                }
            } else {
                String response = "404 Not Found";
                exchange.sendResponseHeaders(404, response.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
            }
        }

        private String getContentType(Path file) {
            String name = file.getFileName().toString();
            if (name.endsWith(".html")) return "text/html";
            if (name.endsWith(".css")) return "text/css";
            if (name.endsWith(".js")) return "application/javascript";
            if (name.endsWith(".json")) return "application/json";
            if (name.endsWith(".svg")) return "image/svg+xml";
            if (name.endsWith(".puml")) return "text/plain";
            return "application/octet-stream";
        }
    }

    private static void generateWorkflow() {
        Path workflowDir = Path.of(".github/workflows");
        try {
            Files.createDirectories(workflowDir);
            Path workflowFile = workflowDir.resolve("archimo-scan.yml");
            if (Files.exists(workflowFile)) {
                logger.info("Workflow file already exists: " + workflowFile.toAbsolutePath());
                return;
            }

            String repo = System.getenv("GITHUB_REPOSITORY");
            if (repo == null) {
                repo = "ludoo0d0a/archimo"; // fallback
            }

            String content = """
                    name: Archimo Scan

                    on:
                      workflow_dispatch:
                        inputs:
                          url:
                            description: 'GitHub repository URL'
                            required: true
                          appClass:
                            description: 'Main application class (fully qualified name)'
                            required: false

                    jobs:
                      scan:
                        runs-on: ubuntu-latest
                        steps:
                          - name: Checkout Archimo
                            uses: actions/checkout@v4
                            with:
                              repository: '""" + repo + """
                    '

                          - name: Set up JDK 25
                            uses: actions/setup-java@v4
                            with:
                              java-version: '25'
                              distribution: 'temurin'
                              cache: maven

                          - name: Build Archimo
                            run: mvn -B --no-transfer-progress package -DskipTests -pl archimo -am

                          - name: Run Archimo Scan
                            run: |
                              APP_CLASS_ARG=""
                              if [ -n "${{ github.event.inputs.appClass }}" ]; then
                                APP_CLASS_ARG="--app-class=${{ github.event.inputs.appClass }}"
                              fi
                              java -jar archimo/target/archimo-*-all.jar --github-url=${{ github.event.inputs.url }} $APP_CLASS_ARG

                          - name: Upload architecture report
                            uses: actions/upload-artifact@v4
                            with:
                              name: archimo-docs
                              path: archimo-docs/
                    """;
            Files.writeString(workflowFile, content);
            logger.info("Generated GitHub Actions workflow: " + workflowFile.toAbsolutePath());
        } catch (IOException e) {
            logger.error("Failed to generate workflow: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void printUsage() {
        logger.info("Usage:");
        logger.indent();
        logger.info("Classpath mode (run with project + deps on classpath):");
        logger.indent();
        logger.info("java -cp \"<project-cp>:<this-jar>\" fr.geoking.archimo.extract.ArchimoMain --app-class=<fqcn> [--output-dir=<path>] [-v]");
        logger.info("java -cp \"<project-cp>:<this-jar>\" fr.geoking.archimo.extract.ArchimoMain --base-package=<package> [--output-dir=<path>] [-v]");
        logger.unindent();
        logger.info("Project mode (builds with Maven then extracts):");
        logger.indent();
        logger.info("java -jar archimo-all.jar --project-dir=<path> [--app-class=<fqcn>] [--module=<name>] [--output-dir=<path>] [--full-dependency-mode] [--serve] [-v] [--xmx=1g]");
        logger.unindent();
        logger.info("GitHub mode (clones repo, builds with Maven then extracts):");
        logger.indent();
        logger.info("java -jar archimo-all.jar --github-url=<url> [--app-class=<fqcn>] [--module=<name>] [--output-dir=<path>] [--full-dependency-mode] [--serve] [-v] [--xmx=1g]");
        logger.unindent();
        logger.info("Workflow generation:");
        logger.indent();
        logger.info("java -jar archimo-all.jar --generate-workflow");
        logger.unindent();
        logger.unindent();
        logger.info("");
        logger.info("Options:");
        logger.indent();
        logger.info("-o, --output-format     Diagram / export formats (comma-separated): plantuml, mermaid, json");
        logger.info("                        Default: plantuml,mermaid. Example: -o json or -o plantuml,mermaid,json");
        logger.info("-v, --verbose           Enable verbose logging");
        logger.info("--log-file=<path>       Write logs to specified file");
        logger.info("--xmx=<size>            Set JVM maximum heap size (e.g. 2g)");
        logger.info("--xms=<size>            Set JVM initial heap size");
        logger.info("--xss=<size>            Set JVM thread stack size");
        logger.unindent();
    }

    private static final class Config {
        final java.io.File projectDir;
        final String appClass;
        final String basePackage;
        final java.io.File outputDir;
        final String githubUrl;
        final boolean generateWorkflow;
        final boolean fullDependencyMode;
        final String module;
        final boolean serve;
        final boolean verbose;
        final java.io.File logFile;
        final String xmx;
        final String xms;
        final String xss;
        final MessagingScanConcurrency messagingScanConcurrency;
        final boolean internalChild;
        /** When null, {@link ModulithExtractor} uses {@link OutputFormat#DEFAULT_DIAGRAM_FORMATS}. */
        final Set<OutputFormat> outputFormats;

        Config(java.io.File projectDir, String appClass, String basePackage, java.io.File outputDir, String githubUrl, boolean generateWorkflow, boolean fullDependencyMode, String module, boolean serve, boolean verbose, java.io.File logFile, String xmx, String xms, String xss, MessagingScanConcurrency messagingScanConcurrency, boolean internalChild, Set<OutputFormat> outputFormats) {
            this.projectDir = projectDir;
            this.appClass = appClass;
            this.basePackage = basePackage;
            this.outputDir = outputDir;
            this.githubUrl = githubUrl;
            this.generateWorkflow = generateWorkflow;
            this.fullDependencyMode = fullDependencyMode;
            this.module = module;
            this.serve = serve;
            this.verbose = verbose;
            this.logFile = logFile;
            this.xmx = xmx;
            this.xms = xms;
            this.xss = xss;
            this.messagingScanConcurrency = messagingScanConcurrency != null ? messagingScanConcurrency : MessagingScanConcurrency.AUTO;
            this.internalChild = internalChild;
            this.outputFormats = outputFormats;
        }

        static Config parse(String[] args) {
            java.io.File projectDir = null;
            String appClass = null;
            String basePackage = null;
            java.io.File outputDir = null;
            String githubUrl = null;
            boolean generateWorkflow = false;
            boolean fullDependencyMode = false;
            String module = null;
            boolean serve = false;
            boolean verbose = false;
            java.io.File logFile = null;
            String xmx = null;
            String xms = null;
            String xss = null;
            MessagingScanConcurrency messagingScanConcurrency = MessagingScanConcurrency.AUTO;
            boolean internalChild = false;
            Set<OutputFormat> outputFormats = null;
            boolean expectOutputFormatToken = false;
            for (String a : args) {
                if (expectOutputFormatToken) {
                    outputFormats = mergeOutputFormats(outputFormats, OutputFormat.parseCsv(a, msg -> Logger.getInstance().warn(msg)));
                    expectOutputFormatToken = false;
                    continue;
                }
                if (a.startsWith("--project-dir=")) projectDir = new java.io.File(a.substring("--project-dir=".length()));
                else if (a.startsWith("--app-class=")) appClass = a.substring("--app-class=".length()).trim();
                else if (a.startsWith("--base-package=")) basePackage = a.substring("--base-package=".length()).trim();
                else if (a.startsWith("--output-dir=")) outputDir = new java.io.File(a.substring("--output-dir=".length()));
                else if (a.startsWith("--github-url=")) githubUrl = a.substring("--github-url=".length()).trim();
                else if (a.equals("--generate-workflow")) generateWorkflow = true;
                else if (a.equals("--full-dependency-mode")) fullDependencyMode = true;
                else if (a.startsWith("--module=")) module = a.substring("--module=".length()).trim();
                else if (a.equals("--serve")) serve = true;
                else if (a.equals("-v") || a.equals("--verbose")) verbose = true;
                else if (a.startsWith("--log-file=")) logFile = new java.io.File(a.substring("--log-file=".length()));
                else if (a.startsWith("--xmx=")) xmx = a.substring("--xmx=".length()).trim();
                else if (a.startsWith("--xms=")) xms = a.substring("--xms=".length()).trim();
                else if (a.startsWith("--xss=")) xss = a.substring("--xss=".length()).trim();
                else if (a.startsWith("--messaging-scan-concurrency=")) {
                    messagingScanConcurrency = MessagingScanConcurrency.parseCli(a.substring("--messaging-scan-concurrency=".length()));
                } else if (a.equals("--internal-child")) internalChild = true;
                else if (a.equals("-o") || a.equals("--output-format")) expectOutputFormatToken = true;
                else if (a.startsWith("-o=")) outputFormats = mergeOutputFormats(outputFormats, OutputFormat.parseCsv(a.substring("-o=".length()), msg -> Logger.getInstance().warn(msg)));
                else if (a.startsWith("--output-format=")) outputFormats = mergeOutputFormats(outputFormats, OutputFormat.parseCsv(a.substring("--output-format=".length()), msg -> Logger.getInstance().warn(msg)));
            }
            if (expectOutputFormatToken) return null;
            if (projectDir == null && appClass == null && basePackage == null && githubUrl == null && !generateWorkflow) return null;
            if (projectDir != null && !projectDir.isDirectory()) return null;
            return new Config(projectDir, appClass, basePackage, outputDir, githubUrl, generateWorkflow, fullDependencyMode, module, serve, verbose, logFile, xmx, xms, xss, messagingScanConcurrency, internalChild, outputFormats);
        }

        private static Set<OutputFormat> mergeOutputFormats(Set<OutputFormat> acc, Set<OutputFormat> parsed) {
            if (parsed == null || parsed.isEmpty()) {
                return acc;
            }
            if (acc == null) {
                return new LinkedHashSet<>(parsed);
            }
            acc.addAll(parsed);
            return acc;
        }
    }

}
