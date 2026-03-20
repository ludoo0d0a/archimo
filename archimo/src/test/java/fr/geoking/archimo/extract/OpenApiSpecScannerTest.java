package fr.geoking.archimo.extract;

import fr.geoking.archimo.extract.model.OpenApiSpecFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiSpecScannerTest {

    @TempDir
    Path projectDir;

    @Test
    void findsOpenApi3Yaml_skipsTargetDirectory() throws Exception {
        Path target = projectDir.resolve("target");
        Files.createDirectories(target);
        Files.writeString(target.resolve("fake-openapi.yaml"), "openapi: 3.0.0\n");

        Path specDir = projectDir.resolve("src/main/resources");
        Files.createDirectories(specDir);
        Files.writeString(specDir.resolve("api-v1.yaml"), "openapi: 3.0.1\ninfo:\n  title: Demo\n");

        List<OpenApiSpecFile> found = new OpenApiSpecScanner().scan(projectDir);

        assertThat(found).extracting(OpenApiSpecFile::relativePath)
                .contains("src/main/resources/api-v1.yaml")
                .doesNotContain("target/fake-openapi.yaml");
        assertThat(found).filteredOn(f -> f.relativePath().endsWith("api-v1.yaml"))
                .extracting(OpenApiSpecFile::specKind)
                .containsExactly("openapi-3");
    }

    @Test
    void detectsSwagger2Json() throws Exception {
        Files.writeString(projectDir.resolve("swagger.json"), """
                { "swagger": "2.0", "info": { "title": "Legacy" } }
                """);

        List<OpenApiSpecFile> found = new OpenApiSpecScanner().scan(projectDir);
        assertThat(found).singleElement()
                .satisfies(f -> {
                    assertThat(f.relativePath()).isEqualTo("swagger.json");
                    assertThat(f.specKind()).isEqualTo("swagger-2");
                });
    }
}
