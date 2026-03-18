package fr.geoking.archimo;

import fr.geoking.archimo.extract.ModulithExtractor;
import fr.geoking.archimo.extract.model.EventFlow;
import fr.geoking.archimo.extract.model.ExtractResult;
import fr.geoking.archimo.extract.model.ModuleDependency;
import fr.geoking.archimo.model.ModuleEvents;
import fr.geoking.archimo.sample.ecommerce.EcommerceApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.modulith.core.ApplicationModules;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
        assertThat(jsonDir.resolve("extract-result.json")).exists();
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
