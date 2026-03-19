package fr.geoking.archimo.extract.output;

import fr.geoking.archimo.extract.model.ArchitectureInfo;
import fr.geoking.archimo.extract.model.ClassDependency;
import fr.geoking.archimo.extract.model.EndpointFlow;
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
                List.of(
                        new ClassDependency("com.example.petclinic.OwnerController", "com.example.petclinic.OwnerService"),
                        new ClassDependency("com.example.petclinic.OwnerService", "com.example.petclinic.OwnerRepository")
                ),
                List.of(
                        new EndpointFlow("GET", "/owners", "com.example.petclinic.OwnerController", "listOwners"),
                        new EndpointFlow("POST", "/owners", "com.example.petclinic.OwnerController", "createOwner")
                ),
                List.of(),
                List.of(),
                List.of(),
                List.of(
                        new ArchitectureInfo("com.example.petclinic.OwnerController", "controller", "mvc"),
                        new ArchitectureInfo("com.example.petclinic.OwnerService", "service", "mvc"),
                        new ArchitectureInfo("com.example.petclinic.OwnerRepository", "repository", "mvc")
                ),
                false
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
        Path component = outputDir.resolve("architecture-component-dependencies.puml");
        assertThat(component).exists();
        String componentContent = Files.readString(component);
        assertThat(componentContent).contains("com_example_petclinic_OwnerController --> com_example_petclinic_OwnerService");
        assertThat(componentContent).contains("com_example_petclinic_OwnerService --> com_example_petclinic_OwnerRepository");

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

        Path endpointFlow = outputDir.resolve("endpoint-flow.puml");
        assertThat(endpointFlow).exists();
        String endpointFlowContent = Files.readString(endpointFlow);
        assertThat(endpointFlowContent).contains("GET /owners");
        assertThat(endpointFlowContent).contains("OwnerController");

        Path endpointSequenceGet = outputDir.resolve("endpoint-sequence-GET__owners_listOwners.puml");
        assertThat(endpointSequenceGet).exists();
        String endpointSequenceGetContent = Files.readString(endpointSequenceGet);
        assertThat(endpointSequenceGetContent).contains("Client -> OwnerController : GET /owners");
        assertThat(endpointSequenceGetContent).contains("OwnerController -> OwnerService : listOwners()");

        Path endpointSequencePost = outputDir.resolve("endpoint-sequence-POST__owners_createOwner.puml");
        assertThat(endpointSequencePost).exists();
    }

    @Test
    void fullDependencyMode_includesNonLayerEdgesInComponentDiagram() throws Exception {
        ExtractResult result = new ExtractResult(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(
                        new ClassDependency("com.example.petclinic.OwnerController", "com.example.petclinic.OwnerEntity")
                ),
                List.of(
                        new EndpointFlow("GET", "/owners/{id}", "com.example.petclinic.OwnerController", "getOwner")
                ),
                List.of(),
                List.of(),
                List.of(),
                List.of(
                        new ArchitectureInfo("com.example.petclinic.OwnerController", "controller", "mvc"),
                        new ArchitectureInfo("com.example.petclinic.OwnerEntity", "domain", "mvc")
                ),
                true
        );

        new PlantUmlOutput().write(null, outputDir, result);
        String componentContent = Files.readString(outputDir.resolve("architecture-component-dependencies.puml"));
        assertThat(componentContent).contains("com_example_petclinic_OwnerController --> com_example_petclinic_OwnerEntity");
    }
}

