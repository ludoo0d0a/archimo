package fr.geoking.archimo.extract;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves the Spring Boot (or similar) main class from Maven POM or Java sources.
 */
public final class MainClassDiscovery {

    private MainClassDiscovery() {}

    public static String discover(Path projectDir) {
        Path pom = projectDir.resolve("pom.xml");
        if (Files.isRegularFile(pom)) {
            try {
                String content = Files.readString(pom);
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
            } catch (IOException ignored) {
            }
        }
        return findMainClassInSources(projectDir);
    }

    private static String findMainClassInSources(Path projectDir) {
        Path src = projectDir.resolve("src/main/java");
        if (!Files.isDirectory(src)) {
            return null;
        }
        try (var walk = Files.walk(src)) {
            for (Path p : walk.filter(x -> x.toString().endsWith(".java")).toList()) {
                String content = Files.readString(p);
                if (content.contains("@SpringBootApplication")
                        || content.contains("implements ApplicationRunner")
                        || content.contains("implements CommandLineRunner")) {
                    return extractFullClassName(p, src);
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private static String extractFullClassName(Path javaFile, Path srcDir) {
        String relative = srcDir.relativize(javaFile).toString().replace(File.separatorChar, '.');
        if (relative.endsWith(".java")) {
            return relative.substring(0, relative.length() - ".java".length());
        }
        return relative;
    }

    private static String extractTagValue(String content, String openTag, String closeTag) {
        int start = content.indexOf(openTag);
        if (start < 0) {
            return null;
        }
        start += openTag.length();
        int end = content.indexOf(closeTag, start);
        if (end <= start) {
            return null;
        }
        return content.substring(start, end);
    }
}
