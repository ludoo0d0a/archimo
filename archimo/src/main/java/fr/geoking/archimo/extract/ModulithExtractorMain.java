package fr.geoking.archimo.extract;

import fr.geoking.archimo.extract.model.ExtractResult;
import org.springframework.modulith.core.ApplicationModules;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * CLI entry point for the Archimo Modulith Extractor.
 */
public final class ModulithExtractorMain {

    public static void main(String[] args) {
        Config config = Config.parse(args);
        if (config == null) {
            printUsage();
            System.exit(1);
        }

        if (config.githubUrl != null) {
            runGithubMode(config);
            return;
        }

        if (config.generateWorkflow) {
            generateWorkflow();
            return;
        }

        if (config.projectDir != null) {
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
                        Files.walk(finalTempDir)
                                .sorted(Comparator.reverseOrder())
                                .map(Path::toFile)
                                .forEach(File::delete);
                    }
                } catch (IOException ignored) {
                }
            }));

            System.out.println("Cloning " + config.githubUrl + " into " + tempDir + "...");

            ProcessBuilder pb = new ProcessBuilder("git", "clone", "--depth", "1", "--", config.githubUrl, ".");
            pb.directory(tempDir.toFile());
            pb.inheritIO();
            int exit = pb.start().waitFor();
            if (exit != 0) {
                System.err.println("Git clone failed.");
                System.exit(1);
            }

            Config newConfig = new Config(tempDir.toFile(), config.appClass, config.basePackage, config.outputDir, null, false, config.fullDependencyMode);
            runProjectMode(newConfig);

        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void runProjectMode(Config config) {
        try {
            Path projectDir = config.projectDir.toPath();
            Path target = projectDir.resolve("target");
            Path classes = target.resolve("classes");
            Path dependencyDir = target.resolve("dependency");

            if (!java.nio.file.Files.isDirectory(classes) || !java.nio.file.Files.isDirectory(dependencyDir)) {
                System.err.println("Project not built. Running: mvn compile dependency:copy-dependencies -DincludeScope=compile");
                int exit = runMaven(projectDir, "compile", "dependency:copy-dependencies", "-DincludeScope=compile");
                if (exit != 0) {
                    System.err.println("Maven build failed.");
                    System.exit(1);
                }
            }

            String appClass = config.appClass;
            if (appClass == null) {
                appClass = discoverMainClass(projectDir);
                if (appClass == null) {
                    System.err.println("Could not discover main application class. Use --app-class=fully.qualified.MainClass");
                    System.exit(1);
                }
                System.out.println("Discovered application class: " + appClass);
            }

            String depJars = Files.list(dependencyDir)
                    .filter(p -> p.toString().endsWith(".jar"))
                    .map(Path::toAbsolutePath)
                    .map(Path::toString)
                    .collect(Collectors.joining(java.io.File.pathSeparator));
            String cp = classes.toAbsolutePath() + java.io.File.pathSeparator + depJars + java.io.File.pathSeparator + getCurrentJarPath();

            String outDir = config.outputDir != null ? config.outputDir.toPath().toAbsolutePath().toString() : target.resolve("archimo-docs").toAbsolutePath().toString();

            // Use a Java argument file to avoid very long command lines on Windows (CreateProcess error=206)
            List<String> javaArgs = new ArrayList<>();
            javaArgs.add("-cp");
            javaArgs.add(cp);
            javaArgs.add(ModulithExtractorMain.class.getName());
            javaArgs.add("--app-class=" + appClass);
            javaArgs.add("--output-dir=" + outDir);
            javaArgs.add("--project-dir=" + config.projectDir.getAbsolutePath());
            if (config.fullDependencyMode) {
                javaArgs.add("--full-dependency-mode");
            }

            Path argsFile = Files.createTempFile(target, "archimo-java-args-", ".txt");
            Files.write(argsFile, javaArgs);

            List<String> cmd = new ArrayList<>();
            cmd.add(getJavaExecutable());
            cmd.add("@" + argsFile.toAbsolutePath());

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.inheritIO();
            pb.directory(projectDir.toFile());
            int exit = pb.start().waitFor();
            System.exit(exit);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static String getCurrentJarPath() {
        String path = ModulithExtractorMain.class.getProtectionDomain().getCodeSource().getLocation().getPath();
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

    private static String discoverMainClass(Path projectDir) {
        Path pom = projectDir.resolve("pom.xml");
        if (!java.nio.file.Files.isRegularFile(pom)) return null;
        try {
            String content = java.nio.file.Files.readString(pom);
            if (content.contains("spring-boot-maven-plugin")) {
                String mainClass = extractTagValue(content, "<mainClass>", "</mainClass>");
                if (mainClass != null && !mainClass.isBlank()) {
                    return mainClass.trim();
                }
                String startClass = extractTagValue(content, "<start-class>", "</start-class>");
                if (startClass != null && !startClass.isBlank()) {
                    return startClass.trim();
                }
            }
        } catch (IOException ignored) { }
        return null;
    }

    private static String extractTagValue(String content, String openTag, String closeTag) {
        int start = content.indexOf(openTag);
        if (start < 0) return null;
        start += openTag.length();
        int end = content.indexOf(closeTag, start);
        if (end <= start) return null;
        return content.substring(start, end);
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
                System.err.println("Could not initialize Spring Modulith modules (ignoring if not a Modulith project): " + e.getMessage());
            }

            Path outputDir = config.outputDir != null ? config.outputDir.toPath() : Path.of("archimo-docs");
            Path projectDir = config.projectDir != null ? config.projectDir.toPath() : null;
            ModulithExtractor extractor = new ModulithExtractor(modules, outputDir, projectDir, config.fullDependencyMode);
            ExtractResult result = extractor.extract();

            System.out.println("Extraction complete. Output: " + outputDir.toAbsolutePath());
            System.out.println("  - C4/PlantUML: " + outputDir.resolve("*.puml"));
            System.out.println("  - Module canvases: " + outputDir.resolve("*.adoc"));
            System.out.println("  - Events map & flows: " + outputDir.resolve("json"));
            System.out.println("  - Mermaid: " + outputDir.resolve("mermaid"));

            writeGitHubSummary(result, outputDir);
        } catch (Exception e) {
            e.printStackTrace();
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
            sb.append("- **Architecture Components**: ").append(result.architectureInfos().size()).append("\n\n");

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
            System.out.println("GitHub Actions summary written.");
        } catch (IOException e) {
            System.err.println("Failed to write GitHub Actions summary: " + e.getMessage());
        }
    }

    private static void generateWorkflow() {
        Path workflowDir = Path.of(".github/workflows");
        try {
            Files.createDirectories(workflowDir);
            Path workflowFile = workflowDir.resolve("archimo-scan.yml");
            if (Files.exists(workflowFile)) {
                System.out.println("Workflow file already exists: " + workflowFile.toAbsolutePath());
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

                          - name: Set up JDK 17
                            uses: actions/setup-java@v4
                            with:
                              java-version: '17'
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
            System.out.println("Generated GitHub Actions workflow: " + workflowFile.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("Failed to generate workflow: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void printUsage() {
        System.err.println("Usage:");
        System.err.println("  Classpath mode (run with project + deps on classpath):");
        System.err.println("    java -cp \"<project-cp>:<this-jar>\" fr.geoking.archimo.ModulithExtractorMain --app-class=<fqcn> [--output-dir=<path>]");
        System.err.println("    java -cp \"<project-cp>:<this-jar>\" fr.geoking.archimo.ModulithExtractorMain --base-package=<package> [--output-dir=<path>]");
        System.err.println("  Project mode (builds with Maven then extracts):");
        System.err.println("    java -jar archimo-all.jar --project-dir=<path> [--app-class=<fqcn>] [--output-dir=<path>] [--full-dependency-mode]");
        System.err.println("  GitHub mode (clones repo, builds with Maven then extracts):");
        System.err.println("    java -jar archimo-all.jar --github-url=<url> [--app-class=<fqcn>] [--output-dir=<path>] [--full-dependency-mode]");
        System.err.println("  Workflow generation:");
        System.err.println("    java -jar archimo-all.jar --generate-workflow");
    }

    private static final class Config {
        final java.io.File projectDir;
        final String appClass;
        final String basePackage;
        final java.io.File outputDir;
        final String githubUrl;
        final boolean generateWorkflow;
        final boolean fullDependencyMode;

        Config(java.io.File projectDir, String appClass, String basePackage, java.io.File outputDir, String githubUrl, boolean generateWorkflow, boolean fullDependencyMode) {
            this.projectDir = projectDir;
            this.appClass = appClass;
            this.basePackage = basePackage;
            this.outputDir = outputDir;
            this.githubUrl = githubUrl;
            this.generateWorkflow = generateWorkflow;
            this.fullDependencyMode = fullDependencyMode;
        }

        static Config parse(String[] args) {
            java.io.File projectDir = null;
            String appClass = null;
            String basePackage = null;
            java.io.File outputDir = null;
            String githubUrl = null;
            boolean generateWorkflow = false;
            boolean fullDependencyMode = false;
            for (String a : args) {
                if (a.startsWith("--project-dir=")) projectDir = new java.io.File(a.substring("--project-dir=".length()));
                else if (a.startsWith("--app-class=")) appClass = a.substring("--app-class=".length()).trim();
                else if (a.startsWith("--base-package=")) basePackage = a.substring("--base-package=".length()).trim();
                else if (a.startsWith("--output-dir=")) outputDir = new java.io.File(a.substring("--output-dir=".length()));
                else if (a.startsWith("--github-url=")) githubUrl = a.substring("--github-url=".length()).trim();
                else if (a.equals("--generate-workflow")) generateWorkflow = true;
                else if (a.equals("--full-dependency-mode")) fullDependencyMode = true;
            }
            if (projectDir == null && appClass == null && basePackage == null && githubUrl == null && !generateWorkflow) return null;
            if (projectDir != null && !projectDir.isDirectory()) return null;
            return new Config(projectDir, appClass, basePackage, outputDir, githubUrl, generateWorkflow, fullDependencyMode);
        }
    }
}

