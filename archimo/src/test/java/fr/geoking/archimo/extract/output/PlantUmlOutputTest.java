package fr.geoking.archimo.extract.output;

import fr.geoking.archimo.extract.model.ArchitectureInfo;
import fr.geoking.archimo.extract.model.ExtractResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlantUmlOutputTest {

    @TempDir
    Path outputDir;

    @Test
    void writesClassArchitectureDiagramForNonModulithProjects() throws Exception {
        ExtractResult result = new ExtractResult(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(
                        new ArchitectureInfo("com.example.petclinic.OwnerController", "controller", "mvc"),
                        new ArchitectureInfo("com.example.petclinic.OwnerService", "service", "mvc"),
                        new ArchitectureInfo("com.example.petclinic.OwnerRepository", "repository", "mvc")
                )
        );

        new PlantUmlOutput().write(null, outputDir, result);

        Path diagram = outputDir.resolve("architecture-class-diagram.puml");
        assertThat(diagram).exists();
        String content = Files.readString(diagram);
        assertThat(content).contains("Controller ..> Service : uses");
        assertThat(content).contains("Service ..> Repository : uses");
        assertThat(content).contains("OwnerController");
        assertThat(content).contains("OwnerService");
        assertThat(content).contains("OwnerRepository");

        Path flow = outputDir.resolve("architecture-flow.puml");
        assertThat(flow).exists();
        String flowContent = Files.readString(flow);
        assertThat(flowContent).contains("Controller --> Service");
        assertThat(flowContent).contains("Service --> Repository");

        Path sequence = outputDir.resolve("architecture-sequence.puml");
        assertThat(sequence).exists();
        String sequenceContent = Files.readString(sequence);
        assertThat(sequenceContent).contains("Client -> OwnerController : HTTP request");
        assertThat(sequenceContent).contains("OwnerService -> OwnerRepository : query/persist");
    }
}

