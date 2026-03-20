package fr.geoking.archimo.extract;

import fr.geoking.archimo.extract.model.CommandFlow;
import fr.geoking.archimo.extract.model.ClassDependency;
import fr.geoking.archimo.extract.model.EndpointFlow;
import fr.geoking.archimo.extract.model.EntityRelation;
import fr.geoking.archimo.extract.model.EventFlow;
import fr.geoking.archimo.extract.model.ExtractResult;
import fr.geoking.archimo.extract.model.ModuleDependency;
import fr.geoking.archimo.model.ModuleEvents;
import fr.geoking.archimo.extract.model.SequenceFlow;
import fr.geoking.archimo.extract.output.DiagramOutput;
import fr.geoking.archimo.extract.output.DiagramOutputFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModuleDependency;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.core.DependencyType;
import fr.geoking.archimo.extract.model.ArchitectureInfo;
import fr.geoking.archimo.extract.model.BpmnFlow;
import fr.geoking.archimo.extract.model.MessagingFlow;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.springframework.modulith.core.EventType;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Extracts C4 diagrams (PlantUML), event map, flows and sequences from a Spring Modulith ApplicationModules model.
 */
public final class ModulithExtractor {

    private final ApplicationModules modules;
    private final Path outputDir;
    private final Path projectDir;
    private final boolean fullDependencyMode;
    private final ObjectMapper objectMapper;

    public ModulithExtractor(ApplicationModules modules, Path outputDir) {
        this(modules, outputDir, null, false);
    }

    public ModulithExtractor(ApplicationModules modules, Path outputDir, Path projectDir) {
        this(modules, outputDir, projectDir, false);
    }

    public ModulithExtractor(ApplicationModules modules, Path outputDir, Path projectDir, boolean fullDependencyMode) {
        this.modules = modules;
        this.outputDir = Objects.requireNonNull(outputDir);
        this.projectDir = projectDir;
        this.fullDependencyMode = fullDependencyMode;
        this.objectMapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Run all extractions: C4 diagrams, module canvases, events map, flows, sequences.
     */
    public ExtractResult extract() throws IOException {
        Files.createDirectories(outputDir);

        // 1. Core extraction
        List<ModuleEvents> eventsMap = modules != null ? buildEventsMap() : List.of();
        List<EventFlow> flows = modules != null ? buildEventFlows() : List.of();
        List<SequenceFlow> sequences = modules != null ? buildSequences() : List.of();
        List<ModuleDependency> moduleDependencies = modules != null ? buildModuleDependencies() : List.of();
        List<CommandFlow> commandFlows = modules != null ? buildCommandFlowsFromEventFlows(flows) : List.of();

        // 2. Advanced scanners
        List<ArchitectureInfo> architectureInfos = new ArrayList<>();
        List<ClassDependency> classDependencies = new ArrayList<>();
        List<EntityRelation> entityRelations = new ArrayList<>();
        List<EndpointFlow> endpointFlows = new ArrayList<>();
        List<MessagingFlow> messagingFlows = new ArrayList<>();
        if (projectDir != null) {
            Path classesPath = findClassesPath(projectDir);
            if (classesPath != null && Files.isDirectory(classesPath)) {
                JavaClasses classes = new ClassFileImporter().importPath(classesPath);
                ArchitectureScanner architectureScanner = new ArchitectureScanner();
                architectureInfos = architectureScanner.scan(classes);
                classDependencies = architectureScanner.scanClassDependencies(classes, architectureInfos);
                entityRelations = architectureScanner.scanEntityRelations(classes);
                endpointFlows = new EndpointScanner().scan(classes);
                messagingFlows = new MessagingScanner().scan(classes);
            }
        }
        List<BpmnFlow> bpmnFlows = new BpmnScanner().scan(projectDir);

        ExtractResult result = new ExtractResult(
                eventsMap, flows, sequences, moduleDependencies, classDependencies, entityRelations,
                endpointFlows, commandFlows, messagingFlows, bpmnFlows, architectureInfos, fullDependencyMode
        );

        // 3. Delegate diagram outputs to pluggable writers (PlantUML, Mermaid, …)
        for (DiagramOutput output : DiagramOutputFactory.defaultOutputs()) {
            output.write(modules, outputDir, result);
        }

        // 4. Generate static website (architecture-as-code navigation & search)
        writeSite(eventsMap, flows, endpointFlows, commandFlows, moduleDependencies, architectureInfos, messagingFlows, bpmnFlows);

        // 5. Write JSON artifacts last (use absolute path so output location is unambiguous)
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
        objectMapper.writeValue(jsonDir.resolve("extract-result.json").toFile(), result);

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
                           List<BpmnFlow> bpmnFlows) throws IOException {

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
        List<Map<String, Object>> diagrams = new ArrayList<>();

        // PlantUML files (C4 from Spring Modulith Documenter)
        // C4 levels: 1 = System context (overview), 2 = Container, 3 = Component (per-module), 4 = Code
        try (var paths = Files.walk(outputDir, 5)) {
            paths.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".puml"))
                    .forEach(p -> {
                        try {
                            String relative = outputDir.relativize(p).toString().replace('\\', '/');
                            String fileName = p.getFileName().toString();
                            String id = fileName.substring(0, fileName.length() - ".puml".length());
                            String source = Files.readString(p);
                            int c4Level;
                            String level;
                            String category;
                            String navLabel;
                            if ("components".equals(id) || fileName.toLowerCase().contains("modules") || fileName.toLowerCase().contains("context")) {
                                c4Level = 1;
                                level = "system";
                                category = "overview";
                                navLabel = id.replace("-", " ");
                            } else if (fileName.toLowerCase().contains("container")) {
                                c4Level = 2;
                                level = "container";
                                category = "container";
                                navLabel = "Containers";
                            } else if (id.startsWith("module-")) {
                                c4Level = 3;
                                level = "component";
                                category = "module";
                                navLabel = id.replace("module-", "").replace(".", " / ");
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
                            } else if ("data-lineage-diagram".equals(id)) {
                                c4Level = 4;
                                level = "code";
                                category = "data";
                                navLabel = "Data lineage";
                            } else if ("entity-relationship".equals(id)) {
                                c4Level = 4;
                                level = "code";
                                category = "data";
                                navLabel = "Entity relationship";
                            } else if ("deployment-diagram".equals(id)) {
                                c4Level = 2;
                                level = "container";
                                category = "deployment";
                                navLabel = "Deployment diagram";
                            } else {
                                c4Level = 3;
                                level = "component";
                                category = "module";
                                navLabel = id;
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
                            entry.put("source", source);
                            diagrams.add(entry);
                        } catch (IOException e) {
                            throw new RuntimeException("Failed to read diagram: " + p, e);
                        }
                    });
        }

        // Mermaid files (event flows, sequences, module dependencies)
        Path mermaidDir = outputDir.resolve("mermaid");
        if (Files.isDirectory(mermaidDir)) {
            try (var paths = Files.list(mermaidDir)) {
                paths.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".mmd"))
                        .forEach(p -> {
                            try {
                                String fileName = p.getFileName().toString();
                                String id = fileName.substring(0, fileName.length() - ".mmd".length());
                                String relative = "mermaid/" + fileName;
                                String source = Files.readString(p);
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
                                    level = "container";
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
                                entry.put("c4Level", 0);  // Mermaid: event flows, sequences, dependencies
                                entry.put("navLabel", navLabel);
                                entry.put("format", "mermaid");
                                entry.put("source", source);
                                diagrams.add(entry);
                            } catch (IOException e) {
                                throw new RuntimeException("Failed to read Mermaid diagram: " + p, e);
                            }
                        });
            }
        }

        return diagrams;
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

    private Path findClassesPath(Path projectDir) {
        Path mavenPath = projectDir.resolve("target/classes");
        if (Files.isDirectory(mavenPath)) return mavenPath;
        Path gradlePath = projectDir.resolve("build/classes/java/main");
        if (Files.isDirectory(gradlePath)) return gradlePath;
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
