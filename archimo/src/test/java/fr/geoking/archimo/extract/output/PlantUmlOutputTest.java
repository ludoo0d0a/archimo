package fr.geoking.archimo.extract.output;

import fr.geoking.archimo.extract.model.ArchitectureInfo;
import fr.geoking.archimo.extract.model.ClassDependency;
import fr.geoking.archimo.extract.model.EndpointFlow;
import fr.geoking.archimo.extract.model.EntityRelation;
import fr.geoking.archimo.extract.model.ExtractResult;
import fr.geoking.archimo.extract.model.MessagingFlow;
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

        Path entityRelationship = outputDir.resolve("entity-relationship.puml");
        assertThat(entityRelationship).exists();
        String entityRelationshipContent = Files.readString(entityRelationship);
        assertThat(entityRelationshipContent).contains("Owner");
        assertThat(entityRelationshipContent).contains("Pet");
        assertThat(entityRelationshipContent).contains("one-to-many");

        Path dataLineage = outputDir.resolve("data-lineage-diagram.puml");
        assertThat(dataLineage).exists();
        String dataLineageContent = Files.readString(dataLineage);
        assertThat(dataLineageContent).contains("GET /owners");
        assertThat(dataLineageContent).contains("com_example_petclinic_OwnerRepository --> com_example_petclinic_Pet");

        Path endpointDataLineageGet = outputDir.resolve("endpoint-data-lineage-GET__owners_listOwners.puml");
        assertThat(endpointDataLineageGet).exists();
        String endpointDataLineageGetContent = Files.readString(endpointDataLineageGet);
        assertThat(endpointDataLineageGetContent).contains("GET /owners");
        assertThat(endpointDataLineageGetContent).contains("com_example_petclinic_OwnerRepository --> com_example_petclinic_Pet : query/persist");
        Path deployment = outputDir.resolve("deployment-diagram.puml");
        assertThat(deployment).exists();
        String deploymentContent = Files.readString(deployment);
        assertThat(deploymentContent).contains("Rel(browser, app, \"HTTP\")");
        assertThat(deploymentContent).contains("ContainerDb(db, \"Application Database\", \"SQL\")");
        assertThat(deploymentContent).contains("ContainerQueue(broker, \"Message Broker\", \"Kafka/JMS\")");

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
                true
        );

        new PlantUmlOutput().write(null, outputDir, result);
        String componentContent = Files.readString(outputDir.resolve("architecture-component-dependencies.puml"));
        assertThat(componentContent).contains("com_example_petclinic_OwnerController --> com_example_petclinic_OwnerEntity");
    }
}

