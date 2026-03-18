package fr.geoking.archimo.extract;

import fr.geoking.archimo.extract.model.CommandFlow;
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
import fr.geoking.archimo.extract.ArchitectureScanner;
import fr.geoking.archimo.extract.BpmnScanner;
import fr.geoking.archimo.extract.MessagingScanner;
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

/**
 * Extracts C4 diagrams (PlantUML), event map, flows and sequences from a Spring Modulith ApplicationModules model.
 */
public final class ModulithExtractor {

    private final ApplicationModules modules;
    private final Path outputDir;
    private final Path projectDir;
    private final ObjectMapper objectMapper;

    public ModulithExtractor(ApplicationModules modules, Path outputDir) {
        this(modules, outputDir, null);
    }

    public ModulithExtractor(ApplicationModules modules, Path outputDir, Path projectDir) {
        this.modules = modules;
        this.outputDir = Objects.requireNonNull(outputDir);
        this.projectDir = projectDir;
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
        List<MessagingFlow> messagingFlows = new ArrayList<>();
        if (projectDir != null) {
            Path classesPath = findClassesPath(projectDir);
            if (classesPath != null && Files.isDirectory(classesPath)) {
                JavaClasses classes = new ClassFileImporter().importPath(classesPath);
                architectureInfos = new ArchitectureScanner().scan(classes);
                messagingFlows = new MessagingScanner().scan(classes);
            }
        }
        List<BpmnFlow> bpmnFlows = new BpmnScanner().scan(projectDir);

        ExtractResult result = new ExtractResult(eventsMap, flows, sequences, moduleDependencies, commandFlows, messagingFlows, bpmnFlows, architectureInfos);

        // 3. Delegate diagram outputs to pluggable writers (PlantUML, Mermaid, …)
        for (DiagramOutput output : DiagramOutputFactory.defaultOutputs()) {
            output.write(modules, outputDir, result);
        }

        // 4. Generate static website (architecture-as-code navigation & search)
        writeSite(eventsMap, flows, commandFlows, moduleDependencies, architectureInfos, messagingFlows, bpmnFlows);

        // 5. Write JSON artifacts last (use absolute path so output location is unambiguous)
        Path jsonDir = outputDir.toAbsolutePath().resolve("json");
        Files.createDirectories(jsonDir);
        objectMapper.writeValue(jsonDir.resolve("events-map.json").toFile(), eventsMap);
        objectMapper.writeValue(jsonDir.resolve("event-flows.json").toFile(), flows);
        objectMapper.writeValue(jsonDir.resolve("command-flows.json").toFile(), commandFlows);
        objectMapper.writeValue(jsonDir.resolve("sequences.json").toFile(), sequences);
        objectMapper.writeValue(jsonDir.resolve("module-dependencies.json").toFile(), moduleDependencies);
        objectMapper.writeValue(jsonDir.resolve("extract-result.json").toFile(), result);

        return result;
    }

    private List<ModuleEvents> buildEventsMap() {
        List<ModuleEvents> list = new ArrayList<>();
        for (ApplicationModule module : modules) {
            String name = module.getDisplayName();
            String basePackage = module.getBasePackage().getName();
            List<String> published = module.getPublishedEvents().stream()
                    .map(this::eventTypeName)
                    .toList();
            List<String> listened = toEventTypeNames(module.getEventsListenedTo(modules));
            list.add(new ModuleEvents(name, basePackage, published, listened));
        }
        return list;
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
        List<ModuleDependency> list = new ArrayList<>();
        for (ApplicationModule module : modules) {
            var deps = module.getDirectDependencies(modules,
                    DependencyType.USES_COMPONENT, DependencyType.ENTITY, DependencyType.EVENT_LISTENER);
            deps.stream()
                    .map(ApplicationModuleDependency::getTargetModule)
                    .map(ApplicationModule::getDisplayName)
                    .forEach(target -> list.add(new ModuleDependency(module.getDisplayName(), target)));
        }
        return list;
    }

    /** Commands = event flows whose event type name contains "Command". */
    private List<CommandFlow> buildCommandFlowsFromEventFlows(List<EventFlow> flows) {
        List<CommandFlow> list = new ArrayList<>();
        for (EventFlow f : flows) {
            if (!f.eventType().contains("Command")) {
                continue;
            }
            String target = f.listenerModules().isEmpty()
                    ? f.publisherModule()
                    : f.listenerModules().get(0);
            list.add(new CommandFlow(f.eventType(), target));
        }
        return list;
    }

    /**
     * Generate a small static website under {@code outputDir/site} that lets you
     * browse C4 diagrams and search modules, classes and events.
     */
    private void writeSite(List<ModuleEvents> eventsMap,
                           List<EventFlow> flows,
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
        List<Map<String, Object>> commandsIndex = new ArrayList<>();
        List<Map<String, Object>> architectureIndex = new ArrayList<>();
        List<Map<String, Object>> messagingIndex = new ArrayList<>();
        List<Map<String, Object>> bpmnIndex = new ArrayList<>();

        // Modules and classes
        if (modules != null)
        for (ApplicationModule module : modules) {
            String moduleName = module.getDisplayName();
            String basePackage = module.getBasePackage().getName();

            Map<String, Object> moduleEntry = new LinkedHashMap<>();
            moduleEntry.put("name", moduleName);
            moduleEntry.put("basePackage", basePackage);
            modulesIndex.add(moduleEntry);

            // Spring beans (components) – these are the primary "classes" users care about
            for (Object bean : module.getSpringBeans()) {
                String className = extractSpringBeanTypeName(bean);
                if (className == null) {
                    continue;
                }
                Map<String, Object> cls = new LinkedHashMap<>();
                cls.put("className", className);
                cls.put("kind", "bean");
                cls.put("module", moduleName);
                classesIndex.add(cls);
            }
        }

        // Events (from flows so we get publisher + listeners)
        for (EventFlow flow : flows) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("eventType", flow.eventType());
            ev.put("publisherModule", flow.publisherModule());
            ev.put("listenerModules", flow.listenerModules());
            eventsIndex.add(ev);
        }

        // Commands (command type -> target module)
        for (CommandFlow cf : commandFlows) {
            Map<String, Object> cmd = new LinkedHashMap<>();
            cmd.put("commandType", cf.commandType());
            cmd.put("targetModule", cf.targetModule());
            commandsIndex.add(cmd);
        }

        // Architecture
        for (ArchitectureInfo info : architectureInfos) {
            Map<String, Object> arch = new LinkedHashMap<>();
            arch.put("className", info.className());
            arch.put("layer", info.layer());
            arch.put("type", info.architectureType());
            architectureIndex.add(arch);
        }

        // Messaging
        for (MessagingFlow mf : messagingFlows) {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("technology", mf.technology());
            msg.put("destination", mf.destination());
            msg.put("publisher", mf.publisher());
            msg.put("subscribers", mf.subscribers());
            messagingIndex.add(msg);
        }

        // BPMN
        for (BpmnFlow bf : bpmnFlows) {
            Map<String, Object> bpmn = new LinkedHashMap<>();
            bpmn.put("engine", bf.engine());
            bpmn.put("processId", bf.processId());
            bpmn.put("stepName", bf.stepName());
            bpmn.put("delegate", bf.delegateBean());
            bpmnIndex.add(bpmn);
        }

        // Site index JSON consumed by the SPA
        Map<String, Object> siteIndex = new LinkedHashMap<>();
        siteIndex.put("diagrams", diagrams);
        siteIndex.put("modules", modulesIndex);
        siteIndex.put("classes", classesIndex);
        siteIndex.put("events", eventsIndex);
        siteIndex.put("commands", commandsIndex);
        siteIndex.put("moduleDependencies", moduleDependencies);
        siteIndex.put("architecture", architectureIndex);
        siteIndex.put("messaging", messagingIndex);
        siteIndex.put("bpmn", bpmnIndex);

        objectMapper.writeValue(siteDir.resolve("site-index.json").toFile(), siteIndex);
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
                                String level = "mermaid";
                                String category = id.startsWith("sequence-") ? "sequence" : id.equals("event-flows") ? "flow" : "dependencies";

                                Map<String, Object> entry = new LinkedHashMap<>();
                                entry.put("id", id);
                                entry.put("title", id.replace('-', ' '));
                                entry.put("path", relative);
                                entry.put("level", level);
                                entry.put("category", category);
                                entry.put("c4Level", 0);  // Mermaid: event flows, sequences, dependencies
                                entry.put("navLabel", id.replaceAll("-", " "));
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

