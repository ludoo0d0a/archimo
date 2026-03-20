package fr.geoking.archimo;

import fr.geoking.archimo.extract.model.EventFlow;
import fr.geoking.archimo.extract.model.ExtractResult;
import fr.geoking.archimo.extract.model.InfrastructureTopology;
import fr.geoking.archimo.extract.model.ModuleDependency;
import fr.geoking.archimo.extract.model.SequenceFlow;
import fr.geoking.archimo.extract.model.ArchitectureInfo;
import fr.geoking.archimo.extract.model.ClassDependency;
import fr.geoking.archimo.extract.model.EndpointFlow;
import fr.geoking.archimo.extract.model.EntityRelation;
import fr.geoking.archimo.extract.output.MermaidOutput;
import fr.geoking.archimo.extract.model.MessagingFlow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MermaidOutputTest {

    @TempDir
    Path outputDir;

    @Test
    void mermaidOutput_writesPlaceholders_whenNoFlowsOrDependencies() throws Exception {
        // Petclinic is expected to have modules but (often) no Modulith event flows.
        // We still want the Mermaid diagrams to be renderable/visible in the site UI.
        List<EventFlow> frFlows = List.of();
        List<SequenceFlow> frSequences = List.of(); // (not used by MermaidOutput, but kept for completeness)
        List<ModuleDependency> frDeps = List.of();

        ExtractResult result = new ExtractResult(
                List.of(),   // eventsMap
                frFlows,     // flows
                frSequences, // sequences
                frDeps,      // moduleDependencies
                List.of(),   // classDependencies
                List.of(),   // entityRelations
                List.of(),   // endpointFlows
                List.of(),   // commandFlows
                List.of(),   // messagingFlows
                List.of(),   // bpmnFlows
                List.of(),   // architectureInfos
                List.of(),   // openApiSpecFiles
                List.of(),   // externalHttpClients
                InfrastructureTopology.empty(),
                null,        // applicationMainClass
                false        // fullDependencyMode
        );

        new MermaidOutput().write(null, outputDir, result);

        String eventFlowsMmd = Files.readString(outputDir.resolve("mermaid").resolve("event-flows.mmd"));
        assertThat(eventFlowsMmd).contains("No event flows discovered");

        String moduleDepsMmd = Files.readString(outputDir.resolve("mermaid").resolve("module-dependencies.mmd"));
        assertThat(moduleDepsMmd).contains("No module dependencies discovered");
    }

    @Test
    void mermaidOutput_writesArchitectureClassDiagram_forNonModulithProjects() throws Exception {
        ExtractResult result = new ExtractResult(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(
                        new ClassDependency("com.example.petclinic.OwnerController", "com.example.petclinic.OwnerService"),
                        new ClassDependency("com.example.petclinic.OwnerService", "com.example.petclinic.OwnerRepository"),
                        new ClassDependency("com.example.petclinic.OwnerRepository", "com.example.petclinic.Pet")
                ),
                List.of(
                        new EntityRelation("com.example.petclinic.Owner", "com.example.petclinic.Pet", "one-to-many")
                ),
                List.of(
                        new EndpointFlow("GET", "/owners", "com.example.petclinic.OwnerController", "listOwners"),
                        new EndpointFlow("POST", "/owners", "com.example.petclinic.OwnerController", "createOwner")
                ),
                List.of(),
                List.of(
                        new MessagingFlow("Kafka", "owner-events", "com.example.petclinic.OwnerService", List.of("com.example.petclinic.AuditService"))
                ),
                List.of(),
                List.of(
                        new ArchitectureInfo("com.example.petclinic.OwnerController", "controller", "mvc"),
                        new ArchitectureInfo("com.example.petclinic.OwnerService", "service", "mvc"),
                        new ArchitectureInfo("com.example.petclinic.OwnerRepository", "repository", "mvc")
                ),
                List.of(),
                List.of(),
                InfrastructureTopology.empty(),
                "com.example.petclinic.PetclinicApplication",
                false
        );

        new MermaidOutput().write(null, outputDir, result);

        String architectureMmd = Files.readString(outputDir.resolve("mermaid").resolve("architecture-class-diagram.mmd"));
        assertThat(architectureMmd).contains("OwnerController");
        assertThat(architectureMmd).contains("OwnerService");
        assertThat(architectureMmd).contains("OwnerRepository");
        assertThat(architectureMmd).contains("subgraph layer_controller");
        assertThat(architectureMmd).contains("layer_controller --> layer_service");
        assertThat(architectureMmd).contains("layer_service --> layer_repository");

        String componentMmd = Files.readString(outputDir.resolve("mermaid").resolve("architecture-component-dependencies.mmd"));
        assertThat(componentMmd).contains("com_example_petclinic_OwnerController --> com_example_petclinic_OwnerService");
        assertThat(componentMmd).contains("com_example_petclinic_OwnerService --> com_example_petclinic_OwnerRepository");
        String entityMmd = Files.readString(outputDir.resolve("mermaid").resolve("entity-relationship.mmd"));
        assertThat(entityMmd).contains("com_example_petclinic_Owner");
        assertThat(entityMmd).contains("com_example_petclinic_Pet");
        assertThat(entityMmd).contains("one-to-many");

        String dataLineageMmd = Files.readString(outputDir.resolve("mermaid").resolve("data-lineage-diagram.mmd"));
        assertThat(dataLineageMmd).contains("GET /owners");
        assertThat(dataLineageMmd).contains("com_example_petclinic_OwnerRepository --> com_example_petclinic_Pet");

        Path endpointDataLineageGet = outputDir.resolve("mermaid").resolve("endpoint-data-lineage-GET__owners_listOwners.mmd");
        assertThat(endpointDataLineageGet).exists();
        String endpointDataLineageGetContent = Files.readString(endpointDataLineageGet);
        assertThat(endpointDataLineageGetContent).contains("GET /owners");
        assertThat(endpointDataLineageGetContent).contains("com_example_petclinic_OwnerRepository --> com_example_petclinic_Pet");
        String deploymentMmd = Files.readString(outputDir.resolve("mermaid").resolve("deployment-diagram.mmd"));
        assertThat(deploymentMmd).contains("client -->|HTTP| app");
        assertThat(deploymentMmd).contains("app -->|JPA/SQL| db");
        assertThat(deploymentMmd).contains("app -->|Publish/Consume| broker");

        String flowMmd = Files.readString(outputDir.resolve("mermaid").resolve("architecture-flow.mmd"));
        assertThat(flowMmd).contains("Controller --> Service");
        assertThat(flowMmd).contains("Service --> Repository");

        String sequenceMmd = Files.readString(outputDir.resolve("mermaid").resolve("architecture-sequence.mmd"));
        assertThat(sequenceMmd).contains("Client->>OwnerController: HTTP request");
        assertThat(sequenceMmd).contains("OwnerService->>OwnerRepository: query/persist");

        String endpointFlowMmd = Files.readString(outputDir.resolve("mermaid").resolve("endpoint-flow.mmd"));
        assertThat(endpointFlowMmd).contains("GET /owners");
        assertThat(endpointFlowMmd).contains("OwnerController");

        String endpointSequenceGetMmd = Files.readString(outputDir.resolve("mermaid").resolve("endpoint-sequence-GET__owners_listOwners.mmd"));
        assertThat(endpointSequenceGetMmd).contains("Client->>OwnerController: GET /owners");
        assertThat(endpointSequenceGetMmd).contains("OwnerController->>OwnerService: listOwners()");
        assertThat(outputDir.resolve("mermaid").resolve("endpoint-sequence-POST__owners_createOwner.mmd")).exists();
    }

    @Test
    void mermaidOutput_fullDependencyMode_includesNonLayerEdges() throws Exception {
        ExtractResult result = new ExtractResult(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(
                        new ClassDependency("com.example.petclinic.OwnerController", "com.example.petclinic.OwnerEntity")
                ),
                List.of(),
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
                List.of(),
                List.of(),
                InfrastructureTopology.empty(),
                null,
                true
        );

        new MermaidOutput().write(null, outputDir, result);
        String componentMmd = Files.readString(outputDir.resolve("mermaid").resolve("architecture-component-dependencies.mmd"));
        assertThat(componentMmd).contains("com_example_petclinic_OwnerController --> com_example_petclinic_OwnerEntity");
    }
}

