package fr.geoking.archimo;

import fr.geoking.archimo.extract.MessagingScanConcurrency;
import fr.geoking.archimo.extract.ModulithExtractor;
import fr.geoking.archimo.extract.model.EventFlow;
import fr.geoking.archimo.extract.output.OutputFormat;
import fr.geoking.archimo.extract.model.ExtractResult;
import fr.geoking.archimo.extract.model.FrameworkDesignInsights;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.geoking.archimo.extract.model.ModuleDependency;
import fr.geoking.archimo.extract.model.ModuleEvents;
import fr.geoking.archimo.sample.ecommerce.EcommerceApplication;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.modulith.core.ApplicationModules;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that parse a sample project (simple e-commerce shop) and assert on the extracted
 * modules, events map, flows and dependencies.
 */
class ModulithExtractorTest {

    @TempDir
    Path outputDir;

    @Test
    void parseSampleEcommerceProject_producesExpectedModules() throws Exception {
        ApplicationModules modules = ApplicationModules.of(EcommerceApplication.class);
        Path sampleProjectDir = findSampleProjectDir();
        ModulithExtractor extractor = new ModulithExtractor(modules, outputDir, sampleProjectDir);
        ExtractResult result = extractor.extract();

        assertThat(result.architectureInfos()).isNotEmpty();
        assertThat(result.architectureInfos()).anyMatch(i -> i.layer().equals("service"));

        Set<String> moduleNames = result.eventsMap().stream()
                .map(m -> m.moduleName())
                .collect(Collectors.toSet());

        assertThat(moduleNames).containsExactlyInAnyOrder("Order", "Catalog", "Customer", "Invoice");
    }

    @Test
    void parseSampleEcommerceProject_producesEventsMapWithOrderAndCatalog() throws Exception {
        ApplicationModules modules = ApplicationModules.of(EcommerceApplication.class);
        ModulithExtractor extractor = new ModulithExtractor(modules, outputDir);
        ExtractResult result = extractor.extract();

        List<ModuleEvents> eventsMap = result.eventsMap();

        var orderModule = eventsMap.stream().filter(m -> "Order".equals(m.moduleName())).findFirst().orElseThrow();
        assertThat(orderModule.publishedEvents())
                .contains("OrderCreated");
        // order listens to catalog and customer events (cross-module)
        assertThat(orderModule.eventsListenedTo())
                .contains("StockReserved", "AddressUpdated");

        var catalogModule = eventsMap.stream().filter(m -> "Catalog".equals(m.moduleName())).findFirst().orElseThrow();
        assertThat(catalogModule.eventsListenedTo())
                .contains("OrderCreated");
    }

    @Test
    void parseSampleEcommerceProject_producesEventFlowOrderToCatalog() throws Exception {
        ApplicationModules modules = ApplicationModules.of(EcommerceApplication.class);
        ModulithExtractor extractor = new ModulithExtractor(modules, outputDir);
        ExtractResult result = extractor.extract();

        List<EventFlow> flows = result.flows();
        assertThat(flows).isNotEmpty();
        assertThat(flows).size().isGreaterThanOrEqualTo(12);

        EventFlow orderCreatedFlow = flows.stream()
                .filter(f -> f.eventType().endsWith("OrderCreated"))
                .findFirst()
                .orElseThrow();
        assertThat(orderCreatedFlow.publisherModule()).isEqualTo("Order");
        assertThat(orderCreatedFlow.listenerModules()).contains("Catalog");
    }

    @Test
    void parseSampleEcommerceProject_producesSequences() throws Exception {
        ApplicationModules modules = ApplicationModules.of(EcommerceApplication.class);
        ModulithExtractor extractor = new ModulithExtractor(modules, outputDir);
        ExtractResult result = extractor.extract();

        assertThat(result.sequences()).isNotEmpty();
        assertThat(result.sequences()).anyMatch(s ->
                s.eventType().endsWith("OrderCreated")
                        && "Order".equals(s.publisherModule())
                        && s.listenerModules().contains("Catalog"));
    }

    @Test
    void parseSampleEcommerceProject_producesModuleDependencies() throws Exception {
        ApplicationModules modules = ApplicationModules.of(EcommerceApplication.class);
        ModulithExtractor extractor = new ModulithExtractor(modules, outputDir);
        ExtractResult result = extractor.extract();

        List<ModuleDependency> deps = result.moduleDependencies();
        // catalog listens to order -> dependency from catalog to order (event listener)
        assertThat(deps).anyMatch(d ->
                "Catalog".equals(d.fromModule()) && "Order".equals(d.toModule()));
    }

    @Test
    void parseSampleEcommerceProject_producesCommandFlowsFromEventNames() throws Exception {
        ApplicationModules modules = ApplicationModules.of(EcommerceApplication.class);
        ModulithExtractor extractor = new ModulithExtractor(modules, outputDir);
        ExtractResult result = extractor.extract();

        // Commands = event types whose name contains "Command"; sample has no such published events
        assertThat(result.commandFlows()).isNotNull();
    }

    @Test
    void outputFormatJson_writesArchitectureJson() throws Exception {
        ModulithExtractor extractor = new ModulithExtractor(null, outputDir, null, false,
                MessagingScanConcurrency.AUTO, null, EnumSet.of(OutputFormat.JSON));
        extractor.extract();
        assertThat(outputDir.resolve("architecture.json")).exists();
    }

    @Test
    void parseSampleEcommerceProject_writesJsonArtifacts() throws Exception {
        ApplicationModules modules = ApplicationModules.of(EcommerceApplication.class);
        ModulithExtractor extractor = new ModulithExtractor(modules, outputDir);
        extractor.extract();

        Path jsonDir = outputDir.resolve("json");
        assertThat(jsonDir.resolve("events-map.json")).exists();
        assertThat(jsonDir.resolve("event-flows.json")).exists();
        assertThat(jsonDir.resolve("command-flows.json")).exists();
        assertThat(jsonDir.resolve("sequences.json")).exists();
        assertThat(jsonDir.resolve("module-dependencies.json")).exists();
        assertThat(jsonDir.resolve("entity-relations.json")).exists();
        assertThat(jsonDir.resolve("endpoint-flows.json")).exists();
        assertThat(jsonDir.resolve("endpoint-sequences.json")).exists();
        assertThat(jsonDir.resolve("extract-result.json")).exists();
        assertThat(jsonDir.resolve("infrastructure-topology.json")).exists();
        assertThat(jsonDir.resolve("framework-design-insights.json")).exists();
        assertThat(jsonDir.resolve("c4-report-tree.json")).exists();
    }

    @Test
    void parseSampleEcommerceProject_siteIndexReflectsC4Levels() throws Exception {
        Path sampleProjectDir = findSampleProjectDir();
        Assumptions.assumeTrue(sampleProjectDir != null);

        ApplicationModules modules = ApplicationModules.of(EcommerceApplication.class);
        ModulithExtractor extractor = new ModulithExtractor(modules, outputDir, sampleProjectDir);
        extractor.extract();

        Path siteIndex = outputDir.resolve("site/site-index.json");
        assertThat(siteIndex).exists();
        ObjectMapper om = new ObjectMapper();
        JsonNode root = om.readTree(siteIndex.toFile());
        JsonNode diagrams = root.get("diagrams");
        assertThat(diagrams != null && diagrams.isArray()).isTrue();
        Map<String, Integer> plantUmlLevels = new HashMap<>();
        for (JsonNode e : diagrams) {
            if (!"plantuml".equals(e.path("format").asText())) {
                continue;
            }
            plantUmlLevels.put(e.path("id").asText(), e.path("c4Level").asInt());
        }
        assertThat(plantUmlLevels.get("system-context")).isEqualTo(1);
        assertThat(plantUmlLevels.get("c4-containers")).isEqualTo(2);
        assertThat(plantUmlLevels.get("components")).isEqualTo(3);
        assertThat(plantUmlLevels.get("architecture-flow")).isEqualTo(0);
        assertThat(plantUmlLevels.get("architecture-sequence")).isEqualTo(0);
        assertThat(plantUmlLevels.get("architecture-class-diagram")).isEqualTo(4);

        JsonNode systemContext = null;
        JsonNode components = null;
        JsonNode archClass = null;
        for (JsonNode e : diagrams) {
            String id = e.path("id").asText();
            if ("system-context".equals(id)) {
                systemContext = e;
            } else if ("components".equals(id)) {
                components = e;
            } else if ("architecture-class-diagram".equals(id) && "plantuml".equals(e.path("format").asText())) {
                archClass = e;
            }
        }
        assertThat(systemContext).isNotNull();
        assertThat(systemContext.path("provenanceSource").asText()).isEqualTo("archimo");
        assertThat(systemContext.path("provenanceNotation").asText()).isEqualTo("c4-plantuml");
        assertThat(systemContext.path("provenanceRenderer").asText()).isEqualTo("kroki-plantuml");

        assertThat(components).isNotNull();
        assertThat(components.path("provenanceSource").asText()).isEqualTo("spring-modulith");
        assertThat(components.path("provenanceNotation").asText()).isEqualTo("c4-plantuml");

        assertThat(archClass).isNotNull();
        assertThat(archClass.path("provenanceSource").asText()).isEqualTo("archimo");
        assertThat(archClass.path("provenanceNotation").asText()).isEqualTo("plantuml");

        JsonNode anyMermaid = null;
        for (JsonNode e : diagrams) {
            if ("mermaid".equals(e.path("format").asText())) {
                anyMermaid = e;
                break;
            }
        }
        assertThat(anyMermaid).isNotNull();
        assertThat(anyMermaid.path("provenanceSource").asText()).isEqualTo("archimo");
        assertThat(anyMermaid.path("provenanceNotation").asText()).isEqualTo("mermaid");
        assertThat(anyMermaid.path("provenanceRenderer").asText()).isEqualTo("mermaid-js");
    }

    @Test
    void parseSampleEcommerceProject_writesDiagramOutputs() throws Exception {
        ApplicationModules modules = ApplicationModules.of(EcommerceApplication.class);
        ModulithExtractor extractor = new ModulithExtractor(modules, outputDir);
        extractor.extract();

        // PlantUML: at least an all-modules diagram
        List<Path> pumlFiles = java.nio.file.Files.walk(outputDir, 3)
                .filter(p -> p.toString().endsWith(".puml"))
                .toList();
        assertThat(pumlFiles).isNotEmpty();

        // Mermaid
        Path mermaidDir = outputDir.resolve("mermaid");
        assertThat(mermaidDir).exists();
    }

    private Path findSampleProjectDir() {
        Path p = Path.of("archimo-sample-ecommerce");
        if (Files.isDirectory(p)) return p;
        p = Path.of("../archimo-sample-ecommerce");
        if (Files.isDirectory(p)) return p;
        return null;
    }

    @Test
    void parseNonModulithProject_succeeds() throws Exception {
        // Run without ApplicationModules
        ModulithExtractor extractor = new ModulithExtractor(null, outputDir);
        ExtractResult result = extractor.extract();

        assertThat(result.eventsMap()).isEmpty();
        assertThat(result.architectureInfos()).isEmpty(); // No projectDir provided
        assertThat(result.frameworkDesignInsights()).isEqualTo(FrameworkDesignInsights.empty());
    }

    @Test
    void parseSampleEcommerceWithProjectDir_detectsJmoleculesAndArchUnitDiagrams() throws Exception {
        Path sampleProjectDir = findSampleProjectDir();
        Assumptions.assumeTrue(sampleProjectDir != null);
        Assumptions.assumeTrue(Files.isDirectory(sampleProjectDir.resolve("target/classes")));
        Assumptions.assumeTrue(Files.isDirectory(sampleProjectDir.resolve("target/test-classes")));

        ApplicationModules modules = ApplicationModules.of(EcommerceApplication.class);
        ModulithExtractor extractor = new ModulithExtractor(modules, outputDir, sampleProjectDir);
        ExtractResult result = extractor.extract();

        assertThat(result.frameworkDesignInsights().jmoleculesDeclaredInBuild()).isTrue();
        assertThat(result.frameworkDesignInsights().jmoleculesElements())
                .anyMatch(e -> e.className().endsWith("OrderCreated"));
        assertThat(result.frameworkDesignInsights().archUnitDeclaredInBuild()).isTrue();
        assertThat(result.frameworkDesignInsights().archUnitRuleRefs())
                .anyMatch(r -> r.className().endsWith("SampleArchitectureRules"));

        assertThat(outputDir.resolve("mermaid/jmolecules-model.mmd")).exists();
        assertThat(Files.readString(outputDir.resolve("mermaid/jmolecules-model.mmd"))).contains("OrderCreated");

        assertThat(outputDir.resolve("mermaid/archunit-rules-overview.mmd")).exists();
        assertThat(Files.readString(outputDir.resolve("mermaid/archunit-rules-overview.mmd")))
                .contains("SampleArchitectureRules");
    }

    /**
     * When {@code archimo.generateReport=true}, runs extraction and writes to
     * {@code target/archimo-docs} so CI can archive the report (PlantUML, Mermaid, site).
     */
    @Test
    @EnabledIfSystemProperty(named = "archimo.generateReport", matches = "true")
    void generateReportToTargetWhenPropertySet() throws Exception {
        Path reportDir = Path.of("target/archimo-docs");
        ApplicationModules modules = ApplicationModules.of(EcommerceApplication.class);
        Path sampleProjectDir = findSampleProjectDir();
        ModulithExtractor extractor = new ModulithExtractor(modules, reportDir, sampleProjectDir);
        extractor.extract();

        assertThat(reportDir).exists();
        assertThat(reportDir.resolve("site")).exists();
        assertThat(reportDir.resolve("site").resolve("index.html")).exists();
        assertThat(Files.walk(reportDir, 2).anyMatch(p -> p.toString().endsWith(".puml"))).isTrue();
        assertThat(reportDir.resolve("mermaid")).exists();
    }
}
