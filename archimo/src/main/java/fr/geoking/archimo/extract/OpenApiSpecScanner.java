package fr.geoking.archimo.extract;

import fr.geoking.archimo.extract.model.OpenApiSpecFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Locates OpenAPI / Swagger YAML or JSON files in the project tree (excluding build output and VCS).
 */
public class OpenApiSpecScanner {

    private static final Set<String> SKIP_DIR_NAMES = Set.of(
            "target", "build", ".git", ".svn", ".idea", "node_modules", ".mvn", "dist", "out");

    public List<OpenApiSpecFile> scan(Path projectDir) {
        List<OpenApiSpecFile> found = new ArrayList<>();
        if (projectDir == null || !Files.isDirectory(projectDir)) {
            return found;
        }
        try {
            Files.walkFileTree(projectDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName().toString();
                    if (SKIP_DIR_NAMES.contains(name)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (!attrs.isRegularFile() || !isCandidateName(file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    String kind = sniffSpecKind(file);
                    if (kind != null) {
                        String rel = projectDir.relativize(file).toString().replace('\\', '/');
                        found.add(new OpenApiSpecFile(rel, kind));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            Logger.getInstance().error("Error scanning OpenAPI/Swagger files: " + e.getMessage());
        }
        return found.stream().distinct().toList();
    }

    private boolean isCandidateName(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!(name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".json"))) {
            return false;
        }
        if (name.contains("openapi")
                || name.contains("swagger")
                || name.equals("api-docs.json")
                || name.endsWith("-api.json")
                || name.endsWith("-api.yaml")
                || name.endsWith("-api.yml")) {
            return true;
        }
        // e.g. api-v1.yaml, rest-api.yml — content sniff filters non-spec YAML/JSON
        return name.contains("api") && (name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".json"));
    }

    private static boolean looksLikeOpenApi3Yaml(String lower) {
        int idx = lower.indexOf("openapi:");
        if (idx < 0) {
            return false;
        }
        int j = idx + "openapi:".length();
        while (j < lower.length() && (lower.charAt(j) == ' ' || lower.charAt(j) == '\t')) {
            j++;
        }
        return j < lower.length() && lower.charAt(j) == '3';
    }

    private String sniffSpecKind(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length == 0) {
                return null;
            }
            int n = Math.min(bytes.length, 24_576);
            String head = new String(bytes, 0, n, StandardCharsets.UTF_8);
            String lower = head.toLowerCase(Locale.ROOT);
            if (looksLikeOpenApi3Yaml(lower)
                    || (lower.contains("\"openapi\"") && lower.contains("\"3."))) {
                return "openapi-3";
            }
            if (lower.contains("swagger:") && (lower.contains("2.0") || lower.contains("'2.0") || lower.contains("\"2."))) {
                return "swagger-2";
            }
            if (lower.contains("\"swagger\"") && lower.contains("\"2.")) {
                return "swagger-2";
            }
            if (lower.contains("openapi:") || lower.contains("\"openapi\"")) {
                return "openapi-unknown";
            }
            if (lower.contains("swagger:") || lower.contains("\"swagger\"")) {
                return "swagger-unknown";
            }
            return null;
        } catch (IOException e) {
            return null;
        }
    }
}
