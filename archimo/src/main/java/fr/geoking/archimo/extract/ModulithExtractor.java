package fr.geoking.archimo.extract;

import fr.geoking.archimo.extract.model.CommandFlow;
import fr.geoking.archimo.extract.model.ClassDependency;
import fr.geoking.archimo.extract.model.EndpointFlow;
import fr.geoking.archimo.extract.model.EntityRelation;
import fr.geoking.archimo.extract.model.EventFlow;
import fr.geoking.archimo.extract.model.ExtractResult;
import fr.geoking.archimo.extract.model.FrameworkDesignInsights;
import fr.geoking.archimo.extract.model.ModuleDependency;
import fr.geoking.archimo.extract.model.ModuleEvents;
import fr.geoking.archimo.extract.model.SequenceFlow;
import fr.geoking.archimo.extract.output.DiagramOutput;
import fr.geoking.archimo.extract.output.DiagramOutputFactory;
import fr.geoking.archimo.extract.output.OutputFormat;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModuleDependency;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.core.DependencyType;
import fr.geoking.archimo.extract.model.ArchitectureInfo;
import fr.geoking.archimo.extract.model.BpmnFlow;
import fr.geoking.archimo.extract.model.ExternalHttpClient;
import fr.geoking.archimo.extract.model.InfrastructureTopology;
import fr.geoking.archimo.extract.model.MessagingFlow;
import fr.geoking.archimo.extract.model.OpenApiSpecFile;
import fr.geoking.archimo.extract.model.report.C4ReportTree;
import fr.geoking.archimo.extract.model.report.DiagramIndexSlot;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.springframework.modulith.core.EventType;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Extracts C4 diagrams (PlantUML), event map, flows and sequences from a Spring Modulith ApplicationModules model.
 */
public final class ModulithExtractor {

    private static final Logger logger = Logger.getInstance();

    /** Beyond this, diagram sources are not embedded in site-index.json (loaded on demand from the report). */
    private static final int INLINE_DIAGRAM_SOURCE_LIMIT = 72;

    private final ApplicationModules modules;
    private final Path outputDir;
    private final Path projectDir;
    private final boolean fullDependencyMode;
    private final MessagingScanConcurrency messagingScanConcurrency;
    /** Optional FQCN from CLI/tests; otherwise resolved from bytecode or project layout. */
    private final String applicationMainClassOverride;
    /** When null or empty, {@link OutputFormat#DEFAULT_DIAGRAM_FORMATS} is used. */
    private final Set<OutputFormat> outputFormats;
    private final ObjectMapper objectMapper;

    public ModulithExtractor(ApplicationModules modules, Path outputDir) {
        this(modules, outputDir, null, false, MessagingScanConcurrency.AUTO, null, null);
    }

    public ModulithExtractor(ApplicationModules modules, Path outputDir, Path projectDir) {
        this(modules, outputDir, projectDir, false, MessagingScanConcurrency.AUTO, null, null);
    }

    public ModulithExtractor(ApplicationModules modules, Path outputDir, Path projectDir, boolean fullDependencyMode) {
        this(modules, outputDir, projectDir, fullDependencyMode, MessagingScanConcurrency.AUTO, null, null);
    }

    public ModulithExtractor(ApplicationModules modules, Path outputDir, Path projectDir, boolean fullDependencyMode,
                             MessagingScanConcurrency messagingScanConcurrency) {
        this(modules, outputDir, projectDir, fullDependencyMode, messagingScanConcurrency, null, null);
    }

    public ModulithExtractor(ApplicationModules modules, Path outputDir, Path projectDir, boolean fullDependencyMode,
                             MessagingScanConcurrency messagingScanConcurrency, String applicationMainClassOverride) {
        this(modules, outputDir, projectDir, fullDependencyMode, messagingScanConcurrency, applicationMainClassOverride, null);
    }

    public ModulithExtractor(ApplicationModules modules, Path outputDir, Path projectDir, boolean fullDependencyMode,
                             MessagingScanConcurrency messagingScanConcurrency, String applicationMainClassOverride,
                             Set<OutputFormat> outputFormats) {
        this.modules = modules;
        this.outputDir = Objects.requireNonNull(outputDir);
        this.projectDir = projectDir;
        this.fullDependencyMode = fullDependencyMode;
        this.messagingScanConcurrency = messagingScanConcurrency != null ? messagingScanConcurrency : MessagingScanConcurrency.AUTO;
        this.applicationMainClassOverride = applicationMainClassOverride != null && !applicationMainClassOverride.isBlank()
                ? applicationMainClassOverride.trim()
                : null;
        this.outputFormats = (outputFormats == null || outputFormats.isEmpty())
                ? EnumSet.copyOf(OutputFormat.DEFAULT_DIAGRAM_FORMATS)
                : EnumSet.copyOf(outputFormats);
        this.objectMapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    /** In-memory C4/report structure for the last {@link #extract()} run; drives diagram ordering and site JSON. */
    private C4ReportTree c4ReportTree = C4ReportTree.empty();

    /**
     * Run all extractions: C4 diagrams, module canvases, events map, flows, sequences.
     */
    public ExtractResult extract() throws IOException {
        Files.createDirectories(outputDir);

        logger.info("Extracting architecture insights...");
        logger.indent();

        // 1. Core extraction (synchronous as modules are needed for downstream steps)
        List<ModuleEvents> eventsMap = modules != null ? buildEventsMap() : List.of();
        List<EventFlow> flows = modules != null ? buildEventFlows() : List.of();
        List<SequenceFlow> sequences = modules != null ? buildSequences() : List.of();
        List<ModuleDependency> moduleDependencies = modules != null ? buildModuleDependencies() : List.of();
        List<CommandFlow> commandFlows = modules != null ? buildCommandFlowsFromEventFlows(flows) : List.of();

        // 2. BPMN scan on common pool only; ArchUnit graph must not be traversed concurrently
        BpmnScanner bpmnScanner = new BpmnScanner();
        CompletableFuture<List<BpmnFlow>> bpmnFuture = CompletableFuture.supplyAsync(() -> bpmnScanner.scan(projectDir));
        CompletableFuture<List<OpenApiSpecFile>> openApiFuture = CompletableFuture.supplyAsync(() -> new OpenApiSpecScanner().scan(projectDir));
        CompletableFuture<InfrastructureTopology> infrastructureFuture = CompletableFuture.supplyAsync(() ->
                projectDir != null ? new InfrastructureScanner().scan(projectDir) : InfrastructureTopology.empty());

        final List<ArchitectureInfo> architectureInfos;
        final List<ClassDependency> classDependencies;
        final List<EntityRelation> entityRelations;
        final List<EndpointFlow> endpointFlows;
        final List<MessagingFlow> messagingFlows;
        final List<ExternalHttpClient> externalHttpClients;
        final int classesParsedCount;
        JavaClasses importedClasses = null;
        JavaClasses importedTestClasses = null;

        if (projectDir != null) {
            Path classesPath = findClassesPath(projectDir);
            if (classesPath != null && Files.isDirectory(classesPath)) {
                importedClasses = new ClassFileImporter().importPath(classesPath);
                classesParsedCount = importedClasses.size();

                ArchitectureScanner architectureScanner = new ArchitectureScanner();
                architectureInfos = architectureScanner.scan(importedClasses);
                entityRelations = architectureScanner.scanEntityRelations(importedClasses);
                endpointFlows = new EndpointScanner().scan(importedClasses);
                messagingFlows = new MessagingScanner().scan(importedClasses, messagingScanConcurrency);
                externalHttpClients = new ExternalHttpClientScanner().scan(importedClasses);
                classDependencies = architectureScanner.scanClassDependencies(importedClasses, architectureInfos);

                Path testClassesPath = findTestClassesPath(projectDir);
                if (testClassesPath != null && Files.isDirectory(testClassesPath)) {
                    importedTestClasses = new ClassFileImporter().importPath(testClassesPath);
                }
            } else {
                architectureInfos = List.of();
                classDependencies = List.of();
                entityRelations = List.of();
                endpointFlows = List.of();
                messagingFlows = List.of();
                externalHttpClients = List.of();
                classesParsedCount = 0;
            }
        } else {
            architectureInfos = List.of();
            classDependencies = List.of();
            entityRelations = List.of();
            endpointFlows = List.of();
            messagingFlows = List.of();
            externalHttpClients = List.of();
            classesParsedCount = 0;
        }

        FrameworkDesignInsights frameworkInsights = new FrameworkInsightsScanner().scan(
                importedClasses, importedTestClasses, projectDir, classDependencies);

        List<BpmnFlow> bpmnFlows = bpmnFuture.join();
        List<OpenApiSpecFile> openApiSpecFiles = openApiFuture.join();
        InfrastructureTopology infrastructureTopology = infrastructureFuture.join();
        int bpmnFilesParsed = bpmnScanner.getFilesParsed();

        logger.info("Parsed " + classesParsedCount + " classes and " + bpmnFilesParsed + " BPMN files.");

        String applicationMainClass = resolveApplicationMainClass(importedClasses, applicationMainClassOverride, projectDir);

        ExtractResult result = new ExtractResult(
                eventsMap, flows, sequences, moduleDependencies, classDependencies, entityRelations,
                endpointFlows, commandFlows, messagingFlows, bpmnFlows, architectureInfos,
                openApiSpecFiles, externalHttpClients, infrastructureTopology, applicationMainClass, fullDependencyMode,
                frameworkInsights
        );

        C4ReportTree scannedTree = C4ReportTreeBuilder.build(modules, result);
        Optional<ArchimoManifestLoader.ManifestLoad> manifestLoad = Optional.empty();
        if (projectDir != null) {
            try {
                manifestLoad = ArchimoManifestLoader.loadFull(projectDir);
                if (manifestLoad.isPresent()) {
                    logger.info("Loaded architecture manifest: "
                            + projectDir.resolve(ArchimoManifestLoader.MANIFEST_FILE_NAME).toAbsolutePath());
                }
            } catch (IOException e) {
                logger.warn("Could not read archimo.mf: " + e.getMessage());
            }
        }
        c4ReportTree = C4ReportTreeMerger.merge(manifestLoad.map(ArchimoManifestLoader.ManifestLoad::tree).orElse(null),
                scannedTree, w -> logger.warn(w));

        // 3. Delegate diagram outputs (sequential to avoid conflicts from library writers like Modulith Documenter)
        logger.info("Generating diagrams...");
        logger.indent();
        for (DiagramOutput output : DiagramOutputFactory.create(outputFormats)) {
            output.write(modules, outputDir, result, c4ReportTree);
        }
        logger.unindent();

        // 4. Generate static website (architecture-as-code navigation & search)
        logger.info("Generating static website...");
        JsonNode archimoManifestOriginalJson = null;
        if (manifestLoad.isPresent()) {
            archimoManifestOriginalJson = manifestLoad.get().rawJson();
        }
        writeSite(eventsMap, flows, endpointFlows, commandFlows, moduleDependencies, architectureInfos, messagingFlows, bpmnFlows,
                openApiSpecFiles, externalHttpClients, infrastructureTopology, archimoManifestOriginalJson);

        // 5. Write JSON artifacts last (use absolute path so output location is unambiguous)
        logger.info("Writing JSON artifacts...");
        Path jsonDir = outputDir.toAbsolutePath().resolve("json");
        Files.createDirectories(jsonDir);
        objectMapper.writeValue(jsonDir.resolve("events-map.json").toFile(), eventsMap);
        objectMapper.writeValue(jsonDir.resolve("event-flows.json").toFile(), flows);
        objectMapper.writeValue(jsonDir.resolve("command-flows.json").toFile(), commandFlows);
        objectMapper.writeValue(jsonDir.resolve("sequences.json").toFile(), sequences);
        objectMapper.writeValue(jsonDir.resolve("module-dependencies.json").toFile(), moduleDependencies);
        objectMapper.writeValue(jsonDir.resolve("class-dependencies.json").toFile(), classDependencies);
        objectMapper.writeValue(jsonDir.resolve("entity-relations.json").toFile(), entityRelations);
        objectMapper.writeValue(jsonDir.resolve("endpoint-flows.json").toFile(), endpointFlows);
        objectMapper.writeValue(jsonDir.resolve("endpoint-sequences.json").toFile(), buildEndpointSequencesIndex(endpointFlows));
        objectMapper.writeValue(jsonDir.resolve("open-api-specs.json").toFile(), openApiSpecFiles);
        objectMapper.writeValue(jsonDir.resolve("external-http-clients.json").toFile(), externalHttpClients);
        objectMapper.writeValue(jsonDir.resolve("infrastructure-topology.json").toFile(), infrastructureTopology);
        objectMapper.writeValue(jsonDir.resolve("extract-result.json").toFile(), result);
        objectMapper.writeValue(jsonDir.resolve("framework-design-insights.json").toFile(), result.frameworkDesignInsights());
        objectMapper.writeValue(jsonDir.resolve("c4-report-tree.json").toFile(), c4ReportTree);

        logger.unindent();
        return result;
    }

    private List<ModuleEvents> buildEventsMap() {
        return StreamSupport.stream(modules.spliterator(), false)
                .map(module -> new ModuleEvents(
                        module.getDisplayName(),
                        module.getBasePackage().getName(),
                        module.getPublishedEvents().stream()
                                .map(this::eventTypeName)
                                .toList(),
                        toEventTypeNames(module.getEventsListenedTo(modules))
                ))
                .toList();
    }

    private List<EventFlow> buildEventFlows() {
        Map<String, String> eventToPublisher = new HashMap<>();
        Map<String, List<String>> eventToListeners = new HashMap<>();

        for (ApplicationModule module : modules) {
            for (EventType ev : module.getPublishedEvents()) {
                String eventName = eventTypeName(ev);
                eventToPublisher.put(eventName, module.getDisplayName());
            }
            for (Object listened : module.getEventsListenedTo(modules)) {
                String eventName = toSingleEventTypeName(listened);
                eventToListeners.computeIfAbsent(eventName, k -> new ArrayList<>()).add(module.getDisplayName());
            }
        }

        return eventToPublisher.entrySet().stream()
                .map(e -> new EventFlow(
                        e.getKey(),
                        e.getValue(),
                        eventToListeners.getOrDefault(e.getKey(), List.of())))
                .toList();
    }

    private List<SequenceFlow> buildSequences() {
        return buildEventFlows().stream()
                .map(f -> new SequenceFlow(f.eventType(), f.publisherModule(), f.listenerModules()))
                .toList();
    }

    private List<ModuleDependency> buildModuleDependencies() {
        return StreamSupport.stream(modules.spliterator(), false)
                .flatMap(module -> module.getDirectDependencies(modules,
                                DependencyType.DEFAULT,
                                DependencyType.USES_COMPONENT,
                                DependencyType.ENTITY,
                                DependencyType.EVENT_LISTENER)
                        .stream()
                        .map(dep -> new ModuleDependency(module.getDisplayName(), dep.getTargetModule().getDisplayName())))
                .toList();
    }

    /** Commands = event flows whose event type name contains "Command". */
    private List<CommandFlow> buildCommandFlowsFromEventFlows(List<EventFlow> flows) {
        return flows.stream()
                .filter(f -> f.eventType().contains("Command"))
                .map(f -> new CommandFlow(
                        f.eventType(),
                        f.listenerModules().isEmpty() ? f.publisherModule() : f.listenerModules().get(0)
                ))
                .toList();
    }

    /**
     * Generate a small static website under {@code outputDir/site} that lets you
     * browse C4 diagrams and search modules, classes and events.
     */
    private void writeSite(List<ModuleEvents> eventsMap,
                           List<EventFlow> flows,
                           List<EndpointFlow> endpointFlows,
                           List<CommandFlow> commandFlows,
                           List<ModuleDependency> moduleDependencies,
                           List<ArchitectureInfo> architectureInfos,
                           List<MessagingFlow> messagingFlows,
                           List<BpmnFlow> bpmnFlows,
                           List<OpenApiSpecFile> openApiSpecFiles,
                           List<ExternalHttpClient> externalHttpClients,
                           InfrastructureTopology infrastructureTopology,
                           JsonNode archimoManifestOriginal) throws IOException {

        Path siteDir = outputDir.resolve("site");
        Files.createDirectories(siteDir);

        // Copy static assets (HTML/CSS/JS) from classpath
        copySiteAsset("index.html", siteDir);
        copySiteAsset("app.js", siteDir);
        copySiteAsset("styles.css", siteDir);

        // Build diagrams index (based on generated *.puml files)
        List<Map<String, Object>> diagrams = buildDiagramsIndex();

        // Build search index: modules, classes, events, commands, architecture, messaging, bpmn
        List<Map<String, Object>> modulesIndex = new ArrayList<>();
        List<Map<String, Object>> classesIndex = new ArrayList<>();
        List<Map<String, Object>> eventsIndex = new ArrayList<>();
        List<Map<String, Object>> endpointsIndex = new ArrayList<>();
        List<Map<String, Object>> commandsIndex = new ArrayList<>();
        List<Map<String, Object>> architectureIndex = new ArrayList<>();
        List<Map<String, Object>> messagingIndex = new ArrayList<>();
        List<Map<String, Object>> bpmnIndex = new ArrayList<>();
        List<Map<String, Object>> openApiIndex = new ArrayList<>();
        List<Map<String, Object>> externalHttpIndex = new ArrayList<>();
        List<Map<String, Object>> deploymentFilesIndex = new ArrayList<>();
        List<Map<String, Object>> deploymentContainersIndex = new ArrayList<>();
        List<Map<String, Object>> deploymentK8sServicesIndex = new ArrayList<>();
        List<Map<String, Object>> deploymentIngressesIndex = new ArrayList<>();
        List<Map<String, Object>> deploymentExternalSystemsIndex = new ArrayList<>();

        // Modules and classes
        if (modules != null) {
            StreamSupport.stream(modules.spliterator(), false).forEach(module -> {
                String moduleName = module.getDisplayName();
                Map<String, Object> moduleEntry = new LinkedHashMap<>();
                moduleEntry.put("name", moduleName);
                moduleEntry.put("basePackage", module.getBasePackage().getName());
                modulesIndex.add(moduleEntry);

                module.getSpringBeans().stream()
                        .map(this::extractSpringBeanTypeName)
                        .filter(Objects::nonNull)
                        .forEach(className -> {
                            Map<String, Object> cls = new LinkedHashMap<>();
                            cls.put("className", className);
                            cls.put("kind", "bean");
                            cls.put("module", moduleName);
                            classesIndex.add(cls);
                        });
            });
        }

        // Events (from flows so we get publisher + listeners)
        eventsIndex.addAll(flows.stream().map(flow -> {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("eventType", flow.eventType());
            ev.put("publisherModule", flow.publisherModule());
            ev.put("listenerModules", flow.listenerModules());
            return ev;
        }).toList());

        // Commands (command type -> target module)
        commandsIndex.addAll(commandFlows.stream().map(cf -> {
            Map<String, Object> cmd = new LinkedHashMap<>();
            cmd.put("commandType", cf.commandType());
            cmd.put("targetModule", cf.targetModule());
            return cmd;
        }).toList());

        // Endpoints
        endpointsIndex.addAll(endpointFlows.stream().map(ef -> {
            Map<String, Object> endpoint = new LinkedHashMap<>();
            endpoint.put("httpMethod", ef.httpMethod());
            endpoint.put("path", ef.path());
            endpoint.put("controllerClass", ef.controllerClass());
            endpoint.put("controllerMethod", ef.controllerMethod());
            return endpoint;
        }).toList());

        // Architecture
        architectureIndex.addAll(architectureInfos.stream().map(info -> {
            Map<String, Object> arch = new LinkedHashMap<>();
            arch.put("className", info.className());
            arch.put("layer", info.layer());
            arch.put("type", info.architectureType());
            return arch;
        }).toList());

        // Messaging
        messagingIndex.addAll(messagingFlows.stream().map(mf -> {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("technology", mf.technology());
            msg.put("destination", mf.destination());
            msg.put("publisher", mf.publisher());
            msg.put("subscribers", mf.subscribers());
            return msg;
        }).toList());

        // BPMN
        bpmnIndex.addAll(bpmnFlows.stream().map(bf -> {
            Map<String, Object> bpmn = new LinkedHashMap<>();
            bpmn.put("engine", bf.engine());
            bpmn.put("processId", bf.processId());
            bpmn.put("stepName", bf.stepName());
            bpmn.put("delegate", bf.delegateBean());
            return bpmn;
        }).toList());

        openApiIndex.addAll(openApiSpecFiles.stream().map(spec -> {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("relativePath", spec.relativePath());
            o.put("specKind", spec.specKind());
            return o;
        }).toList());

        externalHttpIndex.addAll(externalHttpClients.stream().map(c -> {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("clientKind", c.clientKind());
            h.put("declaringClass", c.declaringClass());
            h.put("detail", c.detail());
            return h;
        }).toList());

        InfrastructureTopology topo = infrastructureTopology != null ? infrastructureTopology : InfrastructureTopology.empty();
        deploymentFilesIndex.addAll(topo.files().stream().map(f -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("relativePath", f.relativePath());
            m.put("kind", f.kind());
            return m;
        }).toList());
        deploymentContainersIndex.addAll(topo.containers().stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", c.name());
            m.put("image", c.image());
            m.put("sourcePath", c.sourcePath());
            m.put("context", c.context());
            m.put("ports", c.ports());
            return m;
        }).toList());
        deploymentK8sServicesIndex.addAll(topo.kubernetesServices().stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", s.name());
            m.put("namespace", s.namespace());
            m.put("type", s.type());
            m.put("ports", s.ports());
            m.put("sourcePath", s.sourcePath());
            return m;
        }).toList());
        deploymentIngressesIndex.addAll(topo.ingresses().stream().map(i -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", i.name());
            m.put("namespace", i.namespace());
            m.put("ingressClassName", i.ingressClassName());
            m.put("hosts", i.hosts());
            m.put("pathHints", i.pathHints());
            m.put("sourcePath", i.sourcePath());
            return m;
        }).toList());
        deploymentExternalSystemsIndex.addAll(topo.externalSystems().stream().map(x -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("category", x.category());
            m.put("label", x.label());
            m.put("evidence", x.evidence());
            m.put("sourcePath", x.sourcePath());
            return m;
        }).toList());

        // Site index JSON consumed by the SPA
        List<Map<String, Object>> endpointSequencesIndex = buildEndpointSequencesIndex(endpointFlows);
        Map<String, Object> siteIndex = new LinkedHashMap<>();
        siteIndex.put("diagrams", diagrams);
        siteIndex.put("modules", modulesIndex);
        siteIndex.put("classes", classesIndex);
        siteIndex.put("events", eventsIndex);
        siteIndex.put("endpoints", endpointsIndex);
        siteIndex.put("endpointSequences", endpointSequencesIndex);
        siteIndex.put("commands", commandsIndex);
        siteIndex.put("moduleDependencies", moduleDependencies);
        siteIndex.put("architecture", architectureIndex);
        siteIndex.put("messaging", messagingIndex);
        siteIndex.put("bpmn", bpmnIndex);
        siteIndex.put("openApiSpecs", openApiIndex);
        siteIndex.put("externalHttpClients", externalHttpIndex);
        siteIndex.put("deploymentFiles", deploymentFilesIndex);
        siteIndex.put("deploymentContainers", deploymentContainersIndex);
        siteIndex.put("deploymentK8sServices", deploymentK8sServicesIndex);
        siteIndex.put("deploymentIngresses", deploymentIngressesIndex);
        siteIndex.put("deploymentExternalSystems", deploymentExternalSystemsIndex);
        siteIndex.put("c4ReportTree", c4ReportTree);
        siteIndex.put("archimoManifestOriginal", archimoManifestOriginal);

        objectMapper.writeValue(siteDir.resolve("site-index.json").toFile(), siteIndex);
    }

    private List<Map<String, Object>> buildEndpointSequencesIndex(List<EndpointFlow> endpointFlows) {
        return endpointFlows.stream().map(endpoint -> {
            String slug = sanitizeForFilename(endpoint.httpMethod() + "_" + endpoint.path() + "_" + endpoint.controllerMethod());
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("httpMethod", endpoint.httpMethod());
            entry.put("path", endpoint.path());
            entry.put("controllerClass", endpoint.controllerClass());
            entry.put("controllerMethod", endpoint.controllerMethod());
            entry.put("plantumlPath", "endpoint-sequence-" + slug + ".puml");
            entry.put("mermaidPath", "mermaid/endpoint-sequence-" + slug + ".mmd");
            return entry;
        }).toList();
    }

    private String sanitizeForFilename(String value) {
        if (value == null) return "null";
        return value.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private void copySiteAsset(String name, Path siteDir) throws IOException {
        String resourcePath = "site/" + name;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                return; // asset missing, ignore
            }
            Files.copy(in, siteDir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private List<Map<String, Object>> buildDiagramsIndex() throws IOException {
        Path siteDir = outputDir.resolve("site").normalize();
        Path mermaidDir = outputDir.resolve("mermaid");

        Map<String, Path> plantPaths = new LinkedHashMap<>();
        try (var paths = Files.walk(outputDir, 5)) {
            paths.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".puml"))
                    .filter(p -> !p.normalize().startsWith(siteDir))
                    .forEach(p -> {
                        String fn = p.getFileName().toString();
                        String id = fn.substring(0, fn.length() - ".puml".length());
                        plantPaths.put(id, p);
                    });
        }

        Map<String, Path> mermaidPaths = new LinkedHashMap<>();
        if (Files.isDirectory(mermaidDir)) {
            try (var paths = Files.list(mermaidDir)) {
                paths.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".mmd"))
                        .sorted()
                        .forEach(p -> {
                            String fn = p.getFileName().toString();
                            String id = fn.substring(0, fn.length() - ".mmd".length());
                            mermaidPaths.put(id, p);
                        });
            }
        }

        int totalFiles = plantPaths.size() + mermaidPaths.size();
        boolean inlineSources = totalFiles <= INLINE_DIAGRAM_SOURCE_LIMIT;

        Set<String> slottedPlant = new HashSet<>();
        Set<String> slottedMmd = new HashSet<>();
        List<Map<String, Object>> diagrams = new ArrayList<>();

        for (DiagramIndexSlot slot : c4ReportTree.diagramSlots()) {
            if ("mermaid".equals(slot.format())) {
                Path p = mermaidPaths.get(slot.diagramId());
                if (p == null || !Files.isRegularFile(p)) {
                    continue;
                }
                Map<String, Object> entry = buildMermaidDiagramIndexEntry(p, inlineSources);
                overlayDiagramSlotMetadata(entry, slot);
                diagrams.add(entry);
                slottedMmd.add(slot.diagramId());
            } else if ("plantuml".equals(slot.format())) {
                Path p = plantPaths.get(slot.diagramId());
                if (p == null || !Files.isRegularFile(p)) {
                    continue;
                }
                Map<String, Object> entry = buildPlantUmlDiagramIndexEntry(p, inlineSources);
                overlayDiagramSlotMetadata(entry, slot);
                diagrams.add(entry);
                slottedPlant.add(slot.diagramId());
            }
        }

        for (Map.Entry<String, Path> e : plantPaths.entrySet()) {
            if (slottedPlant.contains(e.getKey())) {
                continue;
            }
            Map<String, Object> entry = buildPlantUmlDiagramIndexEntry(e.getValue(), inlineSources);
            applyCanonicalC4DiagramMetadata(entry);
            diagrams.add(entry);
        }
        for (Map.Entry<String, Path> e : mermaidPaths.entrySet()) {
            if (slottedMmd.contains(e.getKey())) {
                continue;
            }
            Map<String, Object> entry = buildMermaidDiagramIndexEntry(e.getValue(), inlineSources);
            applyCanonicalC4DiagramMetadata(entry);
            diagrams.add(entry);
        }

        sortDiagramsForSiteIndex(diagrams);
        return diagrams;
    }

    private static void overlayDiagramSlotMetadata(Map<String, Object> entry, DiagramIndexSlot slot) {
        entry.put("c4Level", slot.c4Level());
        entry.put("c4Order", slot.c4Order());
        entry.put("navLabel", slot.navLabel());
        entry.put("level", slot.levelKey());
        entry.put("category", slot.category());
    }

    private static int diagramC4Level(Map<String, Object> d) {
        Object o = d.get("c4Level");
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o != null) {
            try {
                return Integer.parseInt(o.toString());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static int diagramC4Order(Map<String, Object> d) {
        Object o = d.get("c4Order");
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o != null) {
            try {
                return Integer.parseInt(o.toString());
            } catch (NumberFormatException ignored) {
                return 1000;
            }
        }
        return 1000;
    }

    private static void sortDiagramsForSiteIndex(List<Map<String, Object>> diagrams) {
        diagrams.sort((a, b) -> {
            int la = diagramC4Level(a);
            int lb = diagramC4Level(b);
            if (la != lb) {
                return Integer.compare(la, lb);
            }
            int oa = diagramC4Order(a);
            int ob = diagramC4Order(b);
            if (oa != ob) {
                return Integer.compare(oa, ob);
            }
            String na = String.valueOf(a.getOrDefault("navLabel", a.get("id")));
            String nb = String.valueOf(b.getOrDefault("navLabel", b.get("id")));
            return na.compareToIgnoreCase(nb);
        });
    }

    /**
     * Aligns sidebar labels and {@code c4Level} with the documented C4 tree: L1 context, L2 containers,
     * L3 backend composition, L4 code. Parser-specific diagram files are slotted here so lists stay stable.
     */
    private static void applyCanonicalC4DiagramMetadata(Map<String, Object> entry) {
        String id = (String) entry.get("id");
        if (id == null) {
            return;
        }
        String format = Objects.toString(entry.get("format"), "");

        if ("system-context".equals(id)) {
            entry.put("c4Level", 1);
            entry.put("c4Order", 0);
            entry.put("navLabel", "L1 — System context");
            entry.put("level", "system");
            entry.put("category", "overview");
            return;
        }
        if ("c4-containers".equals(id)) {
            entry.put("c4Level", 2);
            entry.put("c4Order", 0);
            entry.put("navLabel", "L2 — Containers");
            entry.put("level", "container");
            entry.put("category", "container");
            return;
        }
        if ("components".equals(id)) {
            entry.put("c4Level", 3);
            entry.put("c4Order", 0);
            entry.put("navLabel", "L3 — Backend (all modules)");
            entry.put("level", "component");
            entry.put("category", "module");
            return;
        }
        if ("architecture-layers".equals(id)) {
            entry.put("c4Level", 3);
            entry.put("c4Order", 5);
            entry.put("navLabel", "L3 — Backend layers");
            entry.put("level", "component");
            entry.put("category", "layers");
            return;
        }
        if (id.startsWith("module-")) {
            entry.put("c4Level", 3);
            entry.put("c4Order", 20);
            String base = id.substring("module-".length()).replace(".", " / ");
            entry.put("navLabel", "L3 — Module: " + base);
            entry.put("level", "component");
            entry.put("category", "module");
            return;
        }
        if ("architecture-class-diagram".equals(id)) {
            entry.put("c4Level", 4);
            entry.put("c4Order", 0);
            entry.put("navLabel", "L4 — Classes by layer");
            entry.put("level", "code");
            entry.put("category", "code");
            return;
        }
        if ("architecture-component-dependencies".equals(id)) {
            entry.put("c4Level", 4);
            entry.put("c4Order", 5);
            entry.put("navLabel", "L4 — Component dependencies");
            entry.put("level", "code");
            entry.put("category", "code");
            return;
        }
        if ("mermaid".equals(format) && "module-dependencies".equals(id)) {
            entry.put("c4Level", 3);
            entry.put("c4Order", 10);
            entry.put("navLabel", "L3 — Module dependencies (Mermaid)");
            entry.put("level", "component");
            entry.put("category", "module");
            return;
        }
        if ("mermaid".equals(format) && "jmolecules-model".equals(id)) {
            entry.put("c4Level", 4);
            entry.put("c4Order", 30);
            entry.put("navLabel", "L4 — jMolecules model");
            entry.put("level", "code");
            entry.put("category", "framework");
        }
    }

    /**
     * C4 static levels (L1–L4) and supporting diagram kinds (dynamic, deployment, data); see
     * {@code https://c4model.com/diagrams}. {@code c4Level == 0} is the supporting-diagrams sidebar bucket.
     */
    private Map<String, Object> buildPlantUmlDiagramIndexEntry(Path p, boolean inlineSources) throws IOException {
        String relative = outputDir.relativize(p).toString().replace('\\', '/');
        String fileName = p.getFileName().toString();
        String id = fileName.substring(0, fileName.length() - ".puml".length());
        int c4Level;
        String level;
        String category;
        String navLabel;
        if ("system-context".equals(id)) {
            c4Level = 1;
            level = "system";
            category = "overview";
            navLabel = "System context";
        } else if ("c4-containers".equals(id)) {
            c4Level = 2;
            level = "container";
            category = "container";
            navLabel = "Containers";
        } else if ("components".equals(id) || id.startsWith("module-")) {
            // Spring Modulith all-modules + per-module views are component-level (L3), not system context.
            c4Level = 3;
            level = "component";
            category = "module";
            navLabel = id.startsWith("module-")
                    ? id.replace("module-", "").replace(".", " / ")
                    : "All modules";
        } else if ("architecture-layers".equals(id)) {
            c4Level = 3;
            level = "component";
            category = "layers";
            navLabel = "Architecture layers";
        } else if ("architecture-class-diagram".equals(id) || "architecture-component-dependencies".equals(id)) {
            c4Level = 4;
            level = "code";
            category = "code";
            navLabel = "architecture-class-diagram".equals(id) ? "Class diagram" : "Component dependencies";
        } else if ("deployment-diagram".equals(id)) {
            c4Level = 0;
            level = "deployment";
            category = "deployment";
            navLabel = "Deployment";
        } else if ("data-lineage-diagram".equals(id) || "entity-relationship".equals(id)) {
            c4Level = 0;
            level = "data";
            category = "data";
            navLabel = "data-lineage-diagram".equals(id) ? "Data lineage" : "Entity relationship";
        } else if (id.startsWith("endpoint-sequence-")) {
            c4Level = 0;
            level = "endpoint";
            category = "sequence";
            navLabel = formatEndpointSequenceLabel(id);
        } else if ("endpoint-flow".equals(id)) {
            c4Level = 0;
            level = "endpoint";
            category = "flow";
            navLabel = "Endpoint flow";
        } else if (id.startsWith("endpoint-data-lineage-")) {
            c4Level = 0;
            level = "endpoint";
            category = "data";
            navLabel = formatEndpointDataLineageLabel(id);
        } else if ("architecture-flow".equals(id)
                || "architecture-sequence".equals(id)
                || "messaging-flows".equals(id)
                || "bpmn-flows".equals(id)) {
            c4Level = 0;
            level = "dynamic";
            category = switch (id) {
                case "architecture-flow" -> "flow";
                case "architecture-sequence" -> "sequence";
                case "messaging-flows" -> "messaging";
                case "bpmn-flows" -> "bpmn";
                default -> "dynamic";
            };
            navLabel = switch (id) {
                case "architecture-flow" -> "Architecture flow";
                case "architecture-sequence" -> "Architecture sequence";
                case "messaging-flows" -> "Messaging flows";
                case "bpmn-flows" -> "BPMN flows";
                default -> id.replace("-", " ");
            };
        } else {
            c4Level = 0;
            level = "other";
            category = "generated";
            navLabel = id.replace("-", " ");
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", id);
        entry.put("title", fileName);
        entry.put("path", relative);
        entry.put("level", level);
        entry.put("category", category);
        entry.put("c4Level", c4Level);
        entry.put("navLabel", navLabel);
        entry.put("format", "plantuml");
        if (inlineSources) {
            entry.put("source", Files.readString(p));
        } else {
            entry.put("sourcePath", "../" + relative);
        }
        applyDiagramProvenance(entry);
        return entry;
    }

    private Map<String, Object> buildMermaidDiagramIndexEntry(Path p, boolean inlineSources) throws IOException {
        String fileName = p.getFileName().toString();
        String id = fileName.substring(0, fileName.length() - ".mmd".length());
        String relative = "mermaid/" + fileName;
        String level;
        String category;
        String navLabel;
        if (id.startsWith("endpoint-sequence-")) {
            level = "endpoint";
            category = "sequence";
            navLabel = formatEndpointSequenceLabel(id);
        } else if ("endpoint-flow".equals(id)) {
            level = "endpoint";
            category = "flow";
            navLabel = "Endpoint flow";
        } else if (id.startsWith("endpoint-data-lineage-")) {
            level = "endpoint";
            category = "data";
            navLabel = formatEndpointDataLineageLabel(id);
        } else if ("data-lineage-diagram".equals(id)) {
            level = "code";
            category = "data";
            navLabel = "Data lineage";
        } else if ("entity-relationship".equals(id)) {
            level = "code";
            category = "data";
            navLabel = "Entity relationship";
        } else if ("deployment-diagram".equals(id)) {
            level = "deployment";
            category = "deployment";
            navLabel = "Deployment diagram";
        } else {
            level = "mermaid";
            category = id.startsWith("sequence-") ? "sequence" : id.equals("event-flows") ? "flow" : "dependencies";
            navLabel = id.replaceAll("-", " ");
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", id);
        entry.put("title", id.replace('-', ' '));
        entry.put("path", relative);
        entry.put("level", level);
        entry.put("category", category);
        entry.put("c4Level", 0);
        entry.put("navLabel", navLabel);
        entry.put("format", "mermaid");
        if (inlineSources) {
            entry.put("source", Files.readString(p));
        } else {
            entry.put("sourcePath", "../" + relative);
        }
        applyDiagramProvenance(entry);
        return entry;
    }

    /**
     * Classifies each diagram for the site: who generated the source file, which notation is used,
     * and how the SPA renders it (Kroki vs in-browser Mermaid).
     */
    private static void applyDiagramProvenance(Map<String, Object> entry) {
        String format = Objects.toString(entry.get("format"), "");
        String id = Objects.toString(entry.get("id"), "");

        if ("mermaid".equals(format)) {
            entry.put("provenanceSource", "archimo");
            entry.put("provenanceSourceLabel", "Archimo");
            entry.put("provenanceNotation", "mermaid");
            entry.put("provenanceNotationLabel", "Mermaid");
            entry.put("provenanceRenderer", "mermaid-js");
            entry.put("provenanceRendererLabel", "Mermaid (browser)");
            return;
        }

        if (!"plantuml".equals(format)) {
            entry.put("provenanceSource", "unknown");
            entry.put("provenanceSourceLabel", "Unknown");
            entry.put("provenanceNotation", "unknown");
            entry.put("provenanceNotationLabel", "Unknown");
            entry.put("provenanceRenderer", "unknown");
            entry.put("provenanceRendererLabel", "Unknown");
            return;
        }

        boolean springModulith = "components".equals(id) || id.startsWith("module-");
        entry.put("provenanceSource", springModulith ? "spring-modulith" : "archimo");
        entry.put("provenanceSourceLabel", springModulith ? "Spring Modulith" : "Archimo");

        boolean c4PlantUml = springModulith || isArchimoC4PlantUmlDiagramId(id);
        entry.put("provenanceNotation", c4PlantUml ? "c4-plantuml" : "plantuml");
        entry.put("provenanceNotationLabel", c4PlantUml ? "C4 · PlantUML" : "PlantUML");

        entry.put("provenanceRenderer", "kroki-plantuml");
        entry.put("provenanceRendererLabel", "Kroki (PlantUML)");
    }

    /**
     * Archimo-generated {@code .puml} that includes C4-PlantUML libraries (distinct from plain class/sequence diagrams).
     */
    private static boolean isArchimoC4PlantUmlDiagramId(String id) {
        return "system-context".equals(id)
                || "c4-containers".equals(id)
                || "architecture-layers".equals(id)
                || "deployment-diagram".equals(id)
                || "messaging-flows".equals(id);
    }

    private String formatEndpointSequenceLabel(String id) {
        // endpoint-sequence-GET__owners_listOwners -> GET /owners
        String prefix = "endpoint-sequence-";
        if (!id.startsWith(prefix)) return id;
        String slug = id.substring(prefix.length());
        int firstUnderscore = slug.indexOf('_');
        if (firstUnderscore <= 0) return id;
        String method = slug.substring(0, firstUnderscore);
        String rest = slug.substring(firstUnderscore + 1);
        int lastUnderscore = rest.lastIndexOf('_');
        String pathPart = lastUnderscore > 0 ? rest.substring(0, lastUnderscore) : rest;
        String path = "/" + pathPart.replaceAll("_+", "/");
        path = path.replaceAll("/+", "/");
        return method + " " + path;
    }

    private String formatEndpointDataLineageLabel(String id) {
        // endpoint-data-lineage-GET__owners_listOwners -> GET /owners
        String prefix = "endpoint-data-lineage-";
        if (!id.startsWith(prefix)) return id;
        String slug = id.substring(prefix.length());
        int firstUnderscore = slug.indexOf('_');
        if (firstUnderscore <= 0) return id;
        String method = slug.substring(0, firstUnderscore);
        String rest = slug.substring(firstUnderscore + 1);
        int lastUnderscore = rest.lastIndexOf('_');
        String pathPart = lastUnderscore > 0 ? rest.substring(0, lastUnderscore) : rest;
        String path = "/" + pathPart.replaceAll("_+", "/");
        path = path.replaceAll("/+", "/");
        return method + " " + path + " (data lineage)";
    }

    private String eventTypeName(EventType ev) {
        return toSimpleEventName(ev.getType().getFullName());
    }

    /**
     * Returns the simple class name (no package) for display in events, diagrams and report.
     */
    private static String toSimpleEventName(String fullName) {
        if (fullName == null || fullName.isEmpty()) return fullName;
        int lastDot = fullName.lastIndexOf('.');
        return lastDot >= 0 ? fullName.substring(lastDot + 1) : fullName;
    }

    private List<String> toEventTypeNames(List<?> list) {
        if (list == null) return List.of();
        return list.stream()
                .map(this::toSingleEventTypeName)
                .toList();
    }

    private String toSingleEventTypeName(Object o) {
        if (o == null) return "?";
        String name = javaClassFullName(o);
        if (name != null) {
            return toSimpleEventName(name);
        }
        try {
            var getName = o.getClass().getMethod("getName");
            String n = (String) getName.invoke(o);
            return n != null ? toSimpleEventName(n) : "?";
        } catch (Exception ignored) { }
        return toSimpleEventName(o.toString());
    }

    /**
     * Try to obtain the fully-qualified name from an ArchUnit JavaClass-like object.
     */
    private String javaClassFullName(Object o) {
        if (o == null) {
            return null;
        }
        try {
            var getName = o.getClass().getMethod("getFullName");
            Object value = getName.invoke(o);
            if (value instanceof String s) {
                return s;
            }
        } catch (Exception ignored) { }
        try {
            var getType = o.getClass().getMethod("getType");
            Object type = getType.invoke(o);
            if (type != null) {
                var getFullName = type.getClass().getMethod("getFullName");
                Object value = getFullName.invoke(type);
                if (value instanceof String s) {
                    return s;
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static String resolveApplicationMainClass(JavaClasses classes, String override, Path projectDir) {
        if (override != null) {
            return override;
        }
        if (classes != null) {
            List<String> bootApps = StreamSupport.stream(classes.spliterator(), false)
                    .filter(c -> c.isAnnotatedWith("org.springframework.boot.autoconfigure.SpringBootApplication")
                            || c.isAnnotatedWith("org.springframework.modulith.Modulith"))
                    .map(JavaClass::getFullName)
                    .sorted()
                    .toList();
            if (!bootApps.isEmpty()) {
                return bootApps.get(0);
            }
        }
        if (projectDir != null) {
            String fromLayout = MainClassDiscovery.discover(projectDir);
            if (fromLayout != null) {
                return fromLayout;
            }
        }
        return null;
    }

    private Path findClassesPath(Path projectDir) {
        Path mavenPath = projectDir.resolve("target/classes");
        if (Files.isDirectory(mavenPath)) return mavenPath;
        Path gradlePath = projectDir.resolve("build/classes/java/main");
        if (Files.isDirectory(gradlePath)) return gradlePath;
        return null;
    }

    private Path findTestClassesPath(Path projectDir) {
        Path mavenTest = projectDir.resolve("target/test-classes");
        if (Files.isDirectory(mavenTest)) return mavenTest;
        Path gradleTest = projectDir.resolve("build/classes/java/test");
        if (Files.isDirectory(gradleTest)) return gradleTest;
        return null;
    }

    private String extractSpringBeanTypeName(Object bean) {
        if (bean == null) {
            return null;
        }
        // SpringBean#getType() -> JavaClass#getFullName()
        try {
            var getType = bean.getClass().getMethod("getType");
            Object type = getType.invoke(bean);
            String name = javaClassFullName(type);
            if (name != null) {
                return name;
            }
        } catch (Exception ignored) { }
        return null;
    }
}
