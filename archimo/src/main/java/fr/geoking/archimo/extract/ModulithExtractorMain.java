package fr.geoking.archimo;

import fr.geoking.archimo.extract.ModulithExtractor;
import org.springframework.modulith.core.ApplicationModules;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

        if (config.projectDir != null) {
            runProjectMode(config);
            return;
        }

        runExtraction(config);
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

            String outDir = config.outputDir != null ? config.outputDir.toPath().toAbsolutePath().toString() : target.resolve("modulith-docs").toAbsolutePath().toString();

            // Use a Java argument file to avoid very long command lines on Windows (CreateProcess error=206)
            List<String> javaArgs = new ArrayList<>();
            javaArgs.add("-cp");
            javaArgs.add(cp);
            javaArgs.add(ModulithExtractorMain.class.getName());
            javaArgs.add("--app-class=" + appClass);
            javaArgs.add("--output-dir=" + outDir);

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
            ApplicationModules modules;
            if (config.basePackage != null) {
                modules = ApplicationModules.of(config.basePackage);
            } else if (config.appClass != null) {
                Class<?> app = Class.forName(config.appClass);
                modules = ApplicationModules.of(app);
            } else {
                System.err.println("Either --app-class or --base-package is required.");
                System.exit(1);
                return;
            }

            Path outputDir = config.outputDir != null ? config.outputDir.toPath() : Path.of("modulith-docs");
            ModulithExtractor extractor = new ModulithExtractor(modules, outputDir);
            extractor.extract();

            System.out.println("Extraction complete. Output: " + outputDir.toAbsolutePath());
            System.out.println("  - C4/PlantUML: " + outputDir.resolve("*.puml"));
            System.out.println("  - Module canvases: " + outputDir.resolve("*.adoc"));
            System.out.println("  - Events map & flows: " + outputDir.resolve("json"));
            System.out.println("  - Mermaid: " + outputDir.resolve("mermaid"));
        } catch (ClassNotFoundException e) {
            System.err.println("Application class not found (ensure project classes are on classpath): " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void printUsage() {
        System.err.println("Usage:");
        System.err.println("  Classpath mode (run with project + deps on classpath):");
        System.err.println("    java -cp \"<project-cp>:<this-jar>\" fr.geoking.archimo.ModulithExtractorMain --app-class=<fqcn> [--output-dir=<path>]");
        System.err.println("    java -cp \"<project-cp>:<this-jar>\" fr.geoking.archimo.ModulithExtractorMain --base-package=<package> [--output-dir=<path>]");
        System.err.println("  Project mode (builds with Maven then extracts):");
        System.err.println("    java -jar archimo-all.jar --project-dir=<path> [--app-class=<fqcn>] [--output-dir=<path>]");
    }

    private static final class Config {
        final java.io.File projectDir;
        final String appClass;
        final String basePackage;
        final java.io.File outputDir;

        Config(java.io.File projectDir, String appClass, String basePackage, java.io.File outputDir) {
            this.projectDir = projectDir;
            this.appClass = appClass;
            this.basePackage = basePackage;
            this.outputDir = outputDir;
        }

        static Config parse(String[] args) {
            java.io.File projectDir = null;
            String appClass = null;
            String basePackage = null;
            java.io.File outputDir = null;
            for (String a : args) {
                if (a.startsWith("--project-dir=")) projectDir = new java.io.File(a.substring("--project-dir=".length()));
                else if (a.startsWith("--app-class=")) appClass = a.substring("--app-class=".length()).trim();
                else if (a.startsWith("--base-package=")) basePackage = a.substring("--base-package=".length()).trim();
                else if (a.startsWith("--output-dir=")) outputDir = new java.io.File(a.substring("--output-dir=".length()));
            }
            if (projectDir == null && appClass == null && basePackage == null) return null;
            if (projectDir != null && !projectDir.isDirectory()) return null;
            return new Config(projectDir, appClass, basePackage, outputDir);
        }
    }
}

