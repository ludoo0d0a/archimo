package fr.geoking.archimo;

import fr.geoking.archimo.extract.model.EventFlow;
import fr.geoking.archimo.extract.model.ExtractResult;
import fr.geoking.archimo.extract.model.ModuleDependency;
import fr.geoking.archimo.extract.model.SequenceFlow;
import fr.geoking.archimo.extract.model.ArchitectureInfo;
import fr.geoking.archimo.extract.output.MermaidOutput;
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
                List.of(),   // commandFlows
                List.of(),   // messagingFlows
                List.of(),   // bpmnFlows
                List.of()    // architectureInfos
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
                List.of(),
                List.of(),
                List.of(),
                List.of(
                        new ArchitectureInfo("com.example.petclinic.OwnerController", "controller", "mvc"),
                        new ArchitectureInfo("com.example.petclinic.OwnerService", "service", "mvc"),
                        new ArchitectureInfo("com.example.petclinic.OwnerRepository", "repository", "mvc")
                )
        );

        new MermaidOutput().write(null, outputDir, result);

        String architectureMmd = Files.readString(outputDir.resolve("mermaid").resolve("architecture-class-diagram.mmd"));
        assertThat(architectureMmd).contains("OwnerController");
        assertThat(architectureMmd).contains("OwnerService");
        assertThat(architectureMmd).contains("OwnerRepository");
        assertThat(architectureMmd).contains("OwnerController\"]");
        assertThat(architectureMmd).contains("com_example_petclinic_OwnerController --> com_example_petclinic_OwnerService");
        assertThat(architectureMmd).contains("com_example_petclinic_OwnerService --> com_example_petclinic_OwnerRepository");
    }
}

