package fr.geoking.archimo.extract.output;

import fr.geoking.archimo.extract.model.BpmnFlow;
import fr.geoking.archimo.extract.model.ArchitectureInfo;
import fr.geoking.archimo.extract.model.ClassDependency;
import fr.geoking.archimo.extract.model.EndpointFlow;
import fr.geoking.archimo.extract.model.EntityRelation;
import fr.geoking.archimo.extract.model.EventFlow;
import fr.geoking.archimo.extract.model.ExtractResult;
import fr.geoking.archimo.extract.model.ExternalSystemHint;
import fr.geoking.archimo.extract.model.InfrastructureTopology;
import fr.geoking.archimo.extract.model.MessagingFlow;
import fr.geoking.archimo.extract.model.ModuleDependency;
import org.springframework.modulith.core.ApplicationModules;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Writes Mermaid diagrams for event flows, sequences and module dependencies.
 * Events and commands use different color and shape; command = event type name containing "Command".
 */
public final class MermaidOutput implements DiagramOutput {

    private static final int MAX_ENDPOINT_SPECIFIC_DIAGRAMS = 80;

    @Override
    public void write(ApplicationModules modules, Path outputDir, ExtractResult result) throws IOException {
        List<EventFlow> flows = result.flows();
        List<ModuleDependency> deps = result.moduleDependencies();
        writeMermaidEventAndCommandFlows(outputDir, flows);
        writeMermaidSequences(outputDir, flows);
        writeMermaidModuleDependencies(outputDir, deps);
        writeArchitectureClassDiagram(outputDir, result.architectureInfos());
        writeEntityRelationshipDiagram(outputDir, result.entityRelations());
        writeDeploymentDiagram(outputDir, result.architectureInfos(), result.endpointFlows(), result.messagingFlows(), result.entityRelations(),
                result.infrastructureTopology());
        writeDataLineageDiagram(outputDir, result.endpointFlows(), result.architectureInfos(), result.classDependencies(), result.entityRelations());
        if (result.endpointFlows().size() <= MAX_ENDPOINT_SPECIFIC_DIAGRAMS) {
            writeEndpointDataLineageDiagram(outputDir, result.endpointFlows(), result.architectureInfos(), result.classDependencies(), result.entityRelations());
        }
        writeComponentDependenciesDiagram(outputDir, result.architectureInfos(), result.classDependencies(), result.fullDependencyMode());
        writeArchitectureFlowDiagram(outputDir, result.architectureInfos());
        writeArchitectureSequenceDiagram(outputDir, result.architectureInfos(), result.classDependencies());
        writeEndpointFlowDiagram(outputDir, result.endpointFlows(), result.classDependencies(), result.architectureInfos());
        if (result.endpointFlows().size() <= MAX_ENDPOINT_SPECIFIC_DIAGRAMS) {
            writeEndpointSequenceDiagram(outputDir, result.endpointFlows(), result.classDependencies(), result.architectureInfos());
        }
        writeMessagingFlows(outputDir, result.messagingFlows());
        writeBpmnSequences(outputDir, result.bpmnFlows());
    }

    private void writeMermaidEventAndCommandFlows(Path outputDir, List<EventFlow> flows) throws IOException {
        Path mermaidDir = outputDir.resolve("mermaid");
        Files.createDirectories(mermaidDir);
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(mermaidDir.resolve("event-flows.mmd")))) {
            pw.println("%% Events and commands (command = name contains 'Command'); different color and shape");
            pw.println("flowchart LR");
            if (flows == null || flows.isEmpty()) {
                pw.println("  noEventFlows[\"No event flows discovered\"]");
                return;
            }
            List<String> eventNodeIds = new ArrayList<>();
            List<String> commandNodeIds = new ArrayList<>();
            for (EventFlow f : flows) {
                String pub = sanitizeId(f.publisherModule());
                String evId = sanitizeId(f.eventType());
                boolean isCommand = f.eventType().contains("Command");
                if (isCommand) {
                    if (!commandNodeIds.contains(evId)) commandNodeIds.add(evId);
                } else {
                    if (!eventNodeIds.contains(evId)) eventNodeIds.add(evId);
                }
                for (String listener : f.listenerModules()) {
                    String lis = sanitizeId(listener);
                    pw.println("  " + pub + " --> " + evId + " --> " + lis);
                }
                if (f.listenerModules().isEmpty()) {
                    pw.println("  " + pub + " --> " + evId);
                }
            }
            pw.println("  classDef eventNode fill:#fff3e0,stroke:#e65100,stroke-width:2px,rx:8,ry:8");
            pw.println("  classDef commandNode fill:#e3f2fd,stroke:#1565c0,stroke-width:2px,rx:20,ry:20");
            if (!eventNodeIds.isEmpty()) {
                pw.println("  class " + String.join(",", eventNodeIds) + " eventNode");
            }
            if (!commandNodeIds.isEmpty()) {
                pw.println("  class " + String.join(",", commandNodeIds) + " commandNode");
            }
        }
    }

    private void writeMermaidSequences(Path outputDir, List<EventFlow> flows) throws IOException {
        Path dir = outputDir.resolve("mermaid");
        Files.createDirectories(dir);
        for (EventFlow flow : flows) {
            try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(dir.resolve("sequence-" + sanitizeId(flow.eventType()) + ".mmd")))) {
                pw.println("sequenceDiagram");
                String publisherId = sanitizeId(flow.publisherModule());
                pw.println("  participant " + publisherId + " as " + flow.publisherModule());
                for (String l : flow.listenerModules()) {
                    pw.println("  participant " + sanitizeId(l) + " as " + l);
                }
                pw.print("  " + publisherId + "->>+");
                if (flow.listenerModules().isEmpty()) {
                    pw.println("?: " + flow.eventType());
                } else {
                    pw.println(sanitizeId(flow.listenerModules().get(0)) + ": " + flow.eventType());
                    for (int i = 1; i < flow.listenerModules().size(); i++) {
                        pw.println("  " + sanitizeId(flow.listenerModules().get(i - 1))
                                + "->>+" + sanitizeId(flow.listenerModules().get(i))
                                + ": " + flow.eventType());
                    }
                }
            }
        }
    }

    private void writeMermaidModuleDependencies(Path outputDir, List<ModuleDependency> deps) throws IOException {
        Path dir = outputDir.resolve("mermaid");
        Files.createDirectories(dir);
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(dir.resolve("module-dependencies.mmd")))) {
            pw.println("%% Module dependencies");
            pw.println("flowchart LR");
            if (deps == null || deps.isEmpty()) {
                pw.println("  noModuleDependencies[\"No module dependencies discovered\"]");
                return;
            }
            for (ModuleDependency d : deps) {
                pw.println("  " + sanitizeId(d.fromModule()) + " --> " + sanitizeId(d.toModule()));
            }
        }
    }

    private void writeMessagingFlows(Path outputDir, List<MessagingFlow> flows) throws IOException {
        if (flows.isEmpty()) return;
        Path mermaidDir = outputDir.resolve("mermaid");
        Files.createDirectories(mermaidDir);
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(mermaidDir.resolve("messaging-flows.mmd")))) {
            pw.println("flowchart LR");
            flows.stream()
                    .flatMap(f -> Stream.concat(
                            Stream.of("  " + sanitizeId(f.publisher()) + " -- " + f.technology() + " --> " + sanitizeId(f.destination())),
                            f.subscribers().stream().map(sub -> "  " + sanitizeId(f.destination()) + " --> " + sanitizeId(sub))
                    ))
                    .forEach(pw::println);
        }
    }

    /**
     * Fallback class-level architecture diagram for non-Modulith projects.
     * It groups scanned classes by layer and exposes controller->service->repository flow.
     */
    private void writeArchitectureClassDiagram(Path outputDir, List<ArchitectureInfo> infos) throws IOException {
        if (infos == null || infos.isEmpty()) return;
        Path mermaidDir = outputDir.resolve("mermaid");
        Files.createDirectories(mermaidDir);

        Map<String, List<ArchitectureInfo>> byLayer = new HashMap<>();
        for (ArchitectureInfo info : infos) {
            byLayer.computeIfAbsent(info.layer(), k -> new ArrayList<>()).add(info);
        }

        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(mermaidDir.resolve("architecture-class-diagram.mmd")))) {
            pw.println("%% Class architecture fallback (non-Modulith); layer subgraphs + aggregate flow (avoids O(n²) edges)");
            pw.println("flowchart LR");

            for (Map.Entry<String, List<ArchitectureInfo>> entry : byLayer.entrySet()) {
                List<ArchitectureInfo> inLayer = entry.getValue();
                if (inLayer.isEmpty()) continue;
                String layer = entry.getKey();
                String subgraphId = "layer_" + sanitizeId(layer);
                pw.println("  subgraph " + subgraphId + "[\"" + layerDisplayName(layer) + "\"]");
                for (ArchitectureInfo info : inLayer) {
                    String id = sanitizeId(info.className());
                    pw.println("    " + id + "[\"" + simpleName(info.className()) + "\"]");
                }
                pw.println("  end");
            }

            appendArchitectureLayerFlowEdges(pw, byLayer);

            pw.println("  classDef controller fill:#e3f2fd,stroke:#1565c0,stroke-width:2px");
            pw.println("  classDef service fill:#e8f5e9,stroke:#2e7d32,stroke-width:2px");
            pw.println("  classDef repository fill:#fff3e0,stroke:#ef6c00,stroke-width:2px");
            applyLayerClass(pw, byLayer, "controller");
            applyLayerClass(pw, byLayer, "service");
            applyLayerClass(pw, byLayer, "repository");
        }
    }

    private static String layerDisplayName(String layer) {
        if (layer == null || layer.isBlank()) return layer;
        return Character.toUpperCase(layer.charAt(0)) + layer.substring(1);
    }

    private static void appendArchitectureLayerFlowEdges(PrintWriter pw, Map<String, List<ArchitectureInfo>> byLayer) {
        if (hasClasses(byLayer, "controller") && hasClasses(byLayer, "service")) {
            pw.println("  layer_controller --> layer_service");
        }
        if (hasClasses(byLayer, "service") && hasClasses(byLayer, "repository")) {
            pw.println("  layer_service --> layer_repository");
        }
        if (hasClasses(byLayer, "service") && hasClasses(byLayer, "domain")) {
            pw.println("  layer_service --> layer_domain");
        }
        if (hasClasses(byLayer, "controller") && hasClasses(byLayer, "application")) {
            pw.println("  layer_controller --> layer_application");
        }
        if (hasClasses(byLayer, "application") && hasClasses(byLayer, "infrastructure")) {
            pw.println("  layer_application --> layer_infrastructure");
        }
    }

    private static boolean hasClasses(Map<String, List<ArchitectureInfo>> byLayer, String layer) {
        List<ArchitectureInfo> list = byLayer.get(layer);
        return list != null && !list.isEmpty();
    }

    private void applyLayerClass(PrintWriter pw, Map<String, List<ArchitectureInfo>> byLayer, String layer) {
        List<ArchitectureInfo> infos = byLayer.getOrDefault(layer, List.of());
        if (infos.isEmpty()) return;
        List<String> ids = new ArrayList<>();
        for (ArchitectureInfo info : infos) {
            ids.add(sanitizeId(info.className()));
        }
        pw.println("  class " + String.join(",", ids) + " " + layer);
    }

    private static String simpleName(String fqcn) {
        int idx = fqcn.lastIndexOf('.');
        return idx >= 0 ? fqcn.substring(idx + 1) : fqcn;
    }

    private void writeArchitectureFlowDiagram(Path outputDir, List<ArchitectureInfo> infos) throws IOException {
        if (infos == null || infos.isEmpty()) return;
        Path mermaidDir = outputDir.resolve("mermaid");
        Files.createDirectories(mermaidDir);

        Map<String, List<ArchitectureInfo>> byLayer = groupByLayer(infos);
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(mermaidDir.resolve("architecture-flow.mmd")))) {
            pw.println("%% Layer flow fallback (non-Modulith)");
            pw.println("flowchart LR");
            appendLayerNode(pw, byLayer, "controller", "Controller");
            appendLayerNode(pw, byLayer, "service", "Service");
            appendLayerNode(pw, byLayer, "repository", "Repository");
            appendLayerNode(pw, byLayer, "domain", "Domain");
            appendLayerNode(pw, byLayer, "application", "Application");
            appendLayerNode(pw, byLayer, "infrastructure", "Infrastructure");
            appendLayerFlowEdges(pw, byLayer);
        }
    }

    private void writeEntityRelationshipDiagram(Path outputDir, List<EntityRelation> relations) throws IOException {
        if (relations == null || relations.isEmpty()) return;
        Path mermaidDir = outputDir.resolve("mermaid");
        Files.createDirectories(mermaidDir);
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(mermaidDir.resolve("entity-relationship.mmd")))) {
            pw.println("%% Entity relationship diagram inferred from JPA annotations");
            pw.println("classDiagram");
            List<String> declared = new ArrayList<>();
            for (EntityRelation relation : relations) {
                String from = sanitizeId(relation.fromEntity());
                String to = sanitizeId(relation.toEntity());
                if (!declared.contains(from)) {
                    pw.println("  class " + from + " {");
                    pw.println("    <<Entity>>");
                    pw.println("  }");
                    declared.add(from);
                }
                if (!declared.contains(to)) {
                    pw.println("  class " + to + " {");
                    pw.println("    <<Entity>>");
                    pw.println("  }");
                    declared.add(to);
                }
                pw.println("  " + from + " " + mermaidRelationArrow(relation.relationType()) + " " + to
                        + " : " + relation.relationType());
            }
        }
    }

    private void writeDeploymentDiagram(Path outputDir,
                                        List<ArchitectureInfo> infos,
                                        List<EndpointFlow> endpointFlows,
                                        List<MessagingFlow> messagingFlows,
                                        List<EntityRelation> entityRelations,
                                        InfrastructureTopology infrastructureTopology) throws IOException {
        InfrastructureTopology topo = infrastructureTopology != null ? infrastructureTopology : InfrastructureTopology.empty();
        boolean hasManifestExternals = !topo.externalSystems().isEmpty();
        boolean hasApi = (endpointFlows != null && !endpointFlows.isEmpty()) || containsLayer(infos, "controller");
        boolean hasDb = containsLayer(infos, "repository") || (entityRelations != null && !entityRelations.isEmpty());
        boolean hasMessaging = messagingFlows != null && !messagingFlows.isEmpty();
        if (!hasApi && !hasDb && !hasMessaging && !hasManifestExternals) {
            return;
        }

        Path mermaidDir = outputDir.resolve("mermaid");
        Files.createDirectories(mermaidDir);
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(mermaidDir.resolve("deployment-diagram.mmd")))) {
            pw.println("%% Runtime deployment view");
            pw.println("flowchart LR");
            pw.println("  client[\"Client Browser\"]");
            pw.println("  app[\"Spring Boot Application\"]");
            if (hasApi) {
                pw.println("  client -->|HTTP| app");
            }
            if (hasDb) {
                pw.println("  db[(\"Application Database\")]");
                pw.println("  app -->|JPA/SQL| db");
            }
            if (hasMessaging) {
                pw.println("  broker[(\"Message Broker\")]");
                pw.println("  app -->|Publish/Consume| broker");
            }
            writeManifestExternalSystemsMermaid(pw, topo);
        }
    }

    private static void writeManifestExternalSystemsMermaid(PrintWriter pw, InfrastructureTopology topo) {
        List<ExternalSystemHint> hints = topo.externalSystems();
        for (int i = 0; i < hints.size() && i < 18; i++) {
            ExternalSystemHint h = hints.get(i);
            String id = sanitizeId("manifest_ext_" + i + "_" + h.label());
            String shapeOpen;
            String shapeClose;
            switch (h.category()) {
                case "DATABASE" -> {
                    shapeOpen = "[(\"";
                    shapeClose = "\")]";
                }
                case "MESSAGE_BUS_KAFKA", "MESSAGE_BUS_JMS" -> {
                    shapeOpen = "[(\"";
                    shapeClose = "\")]";
                }
                default -> {
                    shapeOpen = "[\"";
                    shapeClose = "\"]";
                }
            }
            String label = (h.label() + " (" + h.category() + ")").replace("\"", "'");
            pw.println("  " + id + shapeOpen + label + shapeClose);
            pw.println("  app -->|" + mermaidEdgeLabel(h.category()) + "| " + id);
        }
    }

    private static String mermaidEdgeLabel(String category) {
        if (category == null) {
            return "uses";
        }
        return switch (category) {
            case "DATABASE" -> "SQL/driver";
            case "MESSAGE_BUS_KAFKA", "MESSAGE_BUS_JMS" -> "messaging";
            case "OBJECT_STORAGE" -> "S3/API";
            case "HTTP_GATEWAY", "REVERSE_PROXY" -> "HTTP";
            case "CACHE" -> "cache";
            case "SEARCH" -> "search";
            case "CLOUD_PROVIDER", "SAAS_HTTP" -> "HTTPS";
            default -> "integrates";
        };
    }

    private void writeDataLineageDiagram(Path outputDir,
                                          List<EndpointFlow> endpointFlows,
                                          List<ArchitectureInfo> infos,
                                          List<ClassDependency> classDependencies,
                                          List<EntityRelation> entityRelations) throws IOException {
        if (endpointFlows == null || endpointFlows.isEmpty()) return;
        Path mermaidDir = outputDir.resolve("mermaid");
        Files.createDirectories(mermaidDir);

        List<ArchitectureInfo> safeInfos = infos == null ? List.of() : infos;
        List<ClassDependency> safeDeps = classDependencies == null ? List.of() : classDependencies;

        List<ArchitectureInfo> repositories = safeInfos.stream().filter(i -> "repository".equals(i.layer())).toList();
        List<ArchitectureInfo> services = safeInfos.stream().filter(i -> "service".equals(i.layer())).toList();

        java.util.Set<String> entityTypes = new java.util.LinkedHashSet<>();
        if (entityRelations != null) {
            for (EntityRelation r : entityRelations) {
                entityTypes.add(r.fromEntity());
                entityTypes.add(r.toEntity());
            }
        }

        Map<String, List<String>> repoToEntities = new HashMap<>();
        for (ClassDependency dep : safeDeps) {
            if (entityTypes.contains(dep.toClass())
                    && repositories.stream().anyMatch(r -> r.className().equals(dep.fromClass()))) {
                repoToEntities.computeIfAbsent(dep.fromClass(), k -> new ArrayList<>()).add(dep.toClass());
            }
        }

        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(mermaidDir.resolve("data-lineage-diagram.mmd")))) {
            pw.println("%% Data lineage (Endpoint -> Controller -> Service -> Repository -> Entity)");
            pw.println("flowchart LR");
            pw.println("  client((Client))");

            java.util.Set<String> declaredNodes = new java.util.LinkedHashSet<>();

            for (EndpointFlow endpoint : endpointFlows) {
                String endpointId = sanitizeId("ep_" + endpoint.httpMethod() + "_" + endpoint.path() + "_" + endpoint.controllerMethod());
                String controllerId = sanitizeId(endpoint.controllerClass());
                String serviceClass = firstDependencyTarget(endpoint.controllerClass(), services, safeDeps);
                String repositoryClass = serviceClass == null ? null : firstDependencyTarget(serviceClass, repositories, safeDeps);

                pw.println("  " + endpointId + "[\"" + endpoint.httpMethod() + " " + endpoint.path() + "\"]");

                if (declaredNodes.add(controllerId)) {
                    pw.println("  " + controllerId + "[\"" + simpleName(endpoint.controllerClass()) + "\"]");
                }
                pw.println("  client --> " + endpointId);
                pw.println("  " + endpointId + " --> " + controllerId);

                if (serviceClass != null) {
                    String serviceId = sanitizeId(serviceClass);
                    if (declaredNodes.add(serviceId)) {
                        pw.println("  " + serviceId + "[\"" + simpleName(serviceClass) + "\"]");
                    }
                    pw.println("  " + controllerId + " --> " + serviceId);

                    if (repositoryClass != null) {
                        String repoId = sanitizeId(repositoryClass);
                        if (declaredNodes.add(repoId)) {
                            pw.println("  " + repoId + "[\"" + simpleName(repositoryClass) + "\"]");
                        }
                        pw.println("  " + serviceId + " --> " + repoId);

                        List<String> entities = repoToEntities.getOrDefault(repositoryClass, List.of());
                        for (String entity : entities) {
                            String entityId = sanitizeId(entity);
                            if (declaredNodes.add(entityId)) {
                                pw.println("  " + entityId + "[\"" + simpleName(entity) + "\"]");
                            }
                            pw.println("  " + repoId + " --> " + entityId);
                        }
                    }
                }
            }
        }
    }

    private void writeEndpointDataLineageDiagram(Path outputDir,
                                                  List<EndpointFlow> endpointFlows,
                                                  List<ArchitectureInfo> infos,
                                                  List<ClassDependency> classDependencies,
                                                  List<EntityRelation> entityRelations) throws IOException {
        if (endpointFlows == null || endpointFlows.isEmpty()) return;
        Path mermaidDir = outputDir.resolve("mermaid");
        Files.createDirectories(mermaidDir);

        List<ArchitectureInfo> safeInfos = infos == null ? List.of() : infos;
        List<ClassDependency> safeDeps = classDependencies == null ? List.of() : classDependencies;

        List<ArchitectureInfo> repositories = safeInfos.stream().filter(i -> "repository".equals(i.layer())).toList();
        List<ArchitectureInfo> services = safeInfos.stream().filter(i -> "service".equals(i.layer())).toList();

        java.util.Set<String> entityTypes = new java.util.LinkedHashSet<>();
        if (entityRelations != null) {
            for (EntityRelation r : entityRelations) {
                entityTypes.add(r.fromEntity());
                entityTypes.add(r.toEntity());
            }
        }

        Map<String, List<String>> repoToEntities = new HashMap<>();
        for (ClassDependency dep : safeDeps) {
            if (entityTypes.contains(dep.toClass())
                    && repositories.stream().anyMatch(r -> r.className().equals(dep.fromClass()))) {
                repoToEntities.computeIfAbsent(dep.fromClass(), k -> new ArrayList<>()).add(dep.toClass());
            }
        }

        for (EndpointFlow endpoint : endpointFlows) {
            String controllerClass = endpoint.controllerClass();
            String serviceClass = firstDependencyTarget(controllerClass, services, safeDeps);
            String repositoryClass = serviceClass == null ? null : firstDependencyTarget(serviceClass, repositories, safeDeps);

            String endpointId = sanitizeId("ep_" + endpoint.httpMethod() + "_" + endpoint.path() + "_" + endpoint.controllerMethod());
            String controllerId = sanitizeId(controllerClass);
            String serviceId = serviceClass != null ? sanitizeId(serviceClass) : null;
            String repoId = repositoryClass != null ? sanitizeId(repositoryClass) : null;

            String sequenceFileName = "endpoint-data-lineage-" + sanitizeId(endpoint.httpMethod() + "_" + endpoint.path() + "_" + endpoint.controllerMethod()) + ".mmd";
            try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(mermaidDir.resolve(sequenceFileName)))) {
                pw.println("%% Endpoint data lineage");
                pw.println("flowchart LR");
                pw.println("  client((Client))");
                pw.println("  " + endpointId + "[\"" + endpoint.httpMethod() + " " + endpoint.path() + "\"]");
                pw.println("  " + controllerId + "[\"" + simpleName(controllerClass) + "\"]");
                pw.println("  client --> " + endpointId);
                pw.println("  " + endpointId + " --> " + controllerId);

                if (serviceClass != null) {
                    pw.println("  " + serviceId + "[\"" + simpleName(serviceClass) + "\"]");
                    pw.println("  " + controllerId + " --> " + serviceId);
                }

                if (repositoryClass != null) {
                    pw.println("  " + repoId + "[\"" + simpleName(repositoryClass) + "\"]");
                    pw.println("  " + (serviceId != null ? serviceId : controllerId) + " --> " + repoId);

                    List<String> entities = repoToEntities.getOrDefault(repositoryClass, List.of());
                    if (!entities.isEmpty()) {
                        for (String entity : entities) {
                            String entityId = sanitizeId(entity);
                            pw.println("  " + entityId + "[\"" + simpleName(entity) + "\"]");
                            pw.println("  " + repoId + " --> " + entityId);
                        }
                    } else {
                        pw.println("  noEntity[\"No entity lineage discovered\"]");
                        pw.println("  " + repoId + " -.-> noEntity");
                    }
                } else {
                    pw.println("  noRepo[\"No repository discovered\"]");
                    pw.println("  " + controllerId + " -.-> noRepo");
                }
            }
        }
    }

    private void writeArchitectureSequenceDiagram(Path outputDir, List<ArchitectureInfo> infos, List<ClassDependency> classDependencies) throws IOException {
        if (infos == null || infos.isEmpty()) return;
        Path mermaidDir = outputDir.resolve("mermaid");
        Files.createDirectories(mermaidDir);

        Map<String, List<ArchitectureInfo>> byLayer = groupByLayer(infos);
        String controllerClass = firstClassName(byLayer, "controller", null);
        String serviceClass = firstClassName(byLayer, "service", null);
        String repositoryClass = firstClassName(byLayer, "repository", null);

        if (controllerClass != null && classDependencies != null && !classDependencies.isEmpty()) {
            String discoveredService = firstDependencyTarget(controllerClass, byLayer.getOrDefault("service", List.of()), classDependencies);
            if (discoveredService != null) {
                serviceClass = discoveredService;
            }
        }
        if (serviceClass != null && classDependencies != null && !classDependencies.isEmpty()) {
            String discoveredRepository = firstDependencyTarget(serviceClass, byLayer.getOrDefault("repository", List.of()), classDependencies);
            if (discoveredRepository != null) {
                repositoryClass = discoveredRepository;
            }
        }

        String controller = controllerClass != null ? simpleName(controllerClass) : "Controller";
        String service = serviceClass != null ? simpleName(serviceClass) : "Service";
        String repository = repositoryClass != null ? simpleName(repositoryClass) : "Repository";

        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(mermaidDir.resolve("architecture-sequence.mmd")))) {
            pw.println("%% Request sequence fallback (non-Modulith)");
            pw.println("sequenceDiagram");
            pw.println("  participant Client");
            pw.println("  participant " + controller);
            pw.println("  participant " + service);
            pw.println("  participant " + repository);
            pw.println("  Client->>" + controller + ": HTTP request");
            pw.println("  " + controller + "->>" + service + ": invoke use case");
            pw.println("  " + service + "->>" + repository + ": query/persist");
            pw.println("  " + repository + "-->>" + service + ": data");
            pw.println("  " + service + "-->>" + controller + ": response model");
            pw.println("  " + controller + "-->>Client: HTTP response");
        }
    }

    private void writeComponentDependenciesDiagram(Path outputDir,
                                                   List<ArchitectureInfo> infos,
                                                   List<ClassDependency> classDependencies,
                                                   boolean fullDependencyMode) throws IOException {
        if (infos == null || infos.isEmpty() || classDependencies == null || classDependencies.isEmpty()) return;
        Path mermaidDir = outputDir.resolve("mermaid");
        Files.createDirectories(mermaidDir);

        Map<String, String> layerByClass = new HashMap<>();
        for (ArchitectureInfo info : infos) {
            layerByClass.put(info.className(), info.layer());
        }

        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(mermaidDir.resolve("architecture-component-dependencies.mmd")))) {
            pw.println("%% Component dependencies (from bytecode)");
            pw.println("flowchart LR");
            for (ArchitectureInfo info : infos) {
                String id = sanitizeId(info.className());
                pw.println("  " + id + "[\"" + simpleName(info.className()) + "\"]");
            }
            for (ClassDependency dep : classDependencies) {
                if (fullDependencyMode || isInterestingDependency(layerByClass, dep)) {
                    pw.println("  " + sanitizeId(dep.fromClass()) + " --> " + sanitizeId(dep.toClass()));
                }
            }
        }
    }

    private static Map<String, List<ArchitectureInfo>> groupByLayer(List<ArchitectureInfo> infos) {
        Map<String, List<ArchitectureInfo>> byLayer = new HashMap<>();
        for (ArchitectureInfo info : infos) {
            byLayer.computeIfAbsent(info.layer(), k -> new ArrayList<>()).add(info);
        }
        return byLayer;
    }

    private static void appendLayerNode(PrintWriter pw, Map<String, List<ArchitectureInfo>> byLayer, String key, String label) {
        List<ArchitectureInfo> infos = byLayer.get(key);
        if (infos == null || infos.isEmpty()) return;
        pw.println("  " + label + "[\"" + label + " (" + infos.size() + " classes)\"]");
    }

    private static void appendLayerFlowEdges(PrintWriter pw, Map<String, List<ArchitectureInfo>> byLayer) {
        if (byLayer.containsKey("controller") && byLayer.containsKey("service")) {
            pw.println("  Controller --> Service");
        }
        if (byLayer.containsKey("service") && byLayer.containsKey("repository")) {
            pw.println("  Service --> Repository");
        }
        if (byLayer.containsKey("service") && byLayer.containsKey("domain")) {
            pw.println("  Service --> Domain");
        }
        if (byLayer.containsKey("controller") && byLayer.containsKey("application")) {
            pw.println("  Controller --> Application");
        }
        if (byLayer.containsKey("application") && byLayer.containsKey("infrastructure")) {
            pw.println("  Application --> Infrastructure");
        }
    }

    private static String firstClassName(Map<String, List<ArchitectureInfo>> byLayer, String layer, String fallback) {
        List<ArchitectureInfo> infos = byLayer.get(layer);
        if (infos == null || infos.isEmpty()) return fallback;
        return infos.get(0).className();
    }

    private static String firstDependencyTarget(String fromClass, List<ArchitectureInfo> candidateTargets, List<ClassDependency> dependencies) {
        if (fromClass == null || candidateTargets == null || candidateTargets.isEmpty()) return null;
        List<String> candidateNames = candidateTargets.stream().map(ArchitectureInfo::className).toList();
        for (ClassDependency dep : dependencies) {
            if (fromClass.equals(dep.fromClass()) && candidateNames.contains(dep.toClass())) {
                return dep.toClass();
            }
        }
        return null;
    }

    private static boolean isInterestingDependency(Map<String, String> layerByClass, ClassDependency dep) {
        String fromLayer = layerByClass.get(dep.fromClass());
        String toLayer = layerByClass.get(dep.toClass());
        if (fromLayer == null || toLayer == null) return false;
        if (fromLayer.equals("controller") && toLayer.equals("service")) return true;
        if (fromLayer.equals("service") && toLayer.equals("repository")) return true;
        if (fromLayer.equals("service") && toLayer.equals("domain")) return true;
        if (fromLayer.equals("application") && toLayer.equals("infrastructure")) return true;
        return fromLayer.equals("controller") && toLayer.equals("application");
    }

    private void writeEndpointFlowDiagram(Path outputDir,
                                          List<EndpointFlow> endpointFlows,
                                          List<ClassDependency> classDependencies,
                                          List<ArchitectureInfo> architectureInfos) throws IOException {
        if (endpointFlows == null || endpointFlows.isEmpty()) return;
        Path mermaidDir = outputDir.resolve("mermaid");
        Files.createDirectories(mermaidDir);
        Map<String, List<ArchitectureInfo>> byLayer = groupByLayer(architectureInfos);
        List<ArchitectureInfo> services = byLayer.getOrDefault("service", List.of());
        List<ArchitectureInfo> repositories = byLayer.getOrDefault("repository", List.of());

        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(mermaidDir.resolve("endpoint-flow.mmd")))) {
            pw.println("%% Endpoint flow map");
            pw.println("flowchart LR");
            pw.println("  Client((Client))");

            for (EndpointFlow endpoint : endpointFlows) {
                String endpointId = sanitizeId("ep_" + endpoint.httpMethod() + "_" + endpoint.path());
                String controllerId = sanitizeId(endpoint.controllerClass());
                pw.println("  " + endpointId + "[\"" + endpoint.httpMethod() + " " + endpoint.path() + "\"]");
                pw.println("  " + controllerId + "[\"" + simpleName(endpoint.controllerClass()) + "\"]");
                pw.println("  Client --> " + endpointId);
                pw.println("  " + endpointId + " --> " + controllerId);

                String service = firstDependencyTarget(endpoint.controllerClass(), services, classDependencies);
                if (service != null) {
                    String serviceId = sanitizeId(service);
                    pw.println("  " + serviceId + "[\"" + simpleName(service) + "\"]");
                    pw.println("  " + controllerId + " --> " + serviceId);
                    String repository = firstDependencyTarget(service, repositories, classDependencies);
                    if (repository != null) {
                        String repositoryId = sanitizeId(repository);
                        pw.println("  " + repositoryId + "[\"" + simpleName(repository) + "\"]");
                        pw.println("  " + serviceId + " --> " + repositoryId);
                    }
                }
            }
        }
    }

    private void writeEndpointSequenceDiagram(Path outputDir,
                                              List<EndpointFlow> endpointFlows,
                                              List<ClassDependency> classDependencies,
                                              List<ArchitectureInfo> architectureInfos) throws IOException {
        if (endpointFlows == null || endpointFlows.isEmpty()) return;
        Path mermaidDir = outputDir.resolve("mermaid");
        Files.createDirectories(mermaidDir);
        Map<String, List<ArchitectureInfo>> byLayer = groupByLayer(architectureInfos);
        for (EndpointFlow endpoint : endpointFlows) {
            String controllerClass = endpoint.controllerClass();
            String serviceClass = firstDependencyTarget(controllerClass, byLayer.getOrDefault("service", List.of()), classDependencies);
            String repositoryClass = serviceClass == null ? null
                    : firstDependencyTarget(serviceClass, byLayer.getOrDefault("repository", List.of()), classDependencies);
            String controller = simpleName(controllerClass);
            String service = serviceClass != null ? simpleName(serviceClass) : "Service";
            String repository = repositoryClass != null ? simpleName(repositoryClass) : "Repository";

            String sequenceFileName = "endpoint-sequence-" + sanitizeId(endpoint.httpMethod() + "_" + endpoint.path() + "_" + endpoint.controllerMethod()) + ".mmd";
            try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(mermaidDir.resolve(sequenceFileName)))) {
                pw.println("%% Endpoint sequence");
                pw.println("sequenceDiagram");
                pw.println("  participant Client");
                pw.println("  participant " + controller);
                pw.println("  participant " + service);
                pw.println("  participant " + repository);
                pw.println("  Client->>" + controller + ": " + endpoint.httpMethod() + " " + endpoint.path());
                pw.println("  " + controller + "->>" + service + ": " + endpoint.controllerMethod() + "()");
                pw.println("  " + service + "->>" + repository + ": query/persist");
                pw.println("  " + repository + "-->>" + service + ": data");
                pw.println("  " + service + "-->>" + controller + ": response model");
                pw.println("  " + controller + "-->>Client: HTTP response");
            }
        }
    }

    private void writeBpmnSequences(Path outputDir, List<BpmnFlow> flows) throws IOException {
        if (flows.isEmpty()) return;
        Path mermaidDir = outputDir.resolve("mermaid");
        Files.createDirectories(mermaidDir);
        // Group by processId
        Map<String, List<BpmnFlow>> processes = new HashMap<>();
        for (BpmnFlow f : flows) {
            processes.computeIfAbsent(f.processId(), k -> new ArrayList<>()).add(f);
        }

        for (Map.Entry<String, List<BpmnFlow>> entry : processes.entrySet()) {
            try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(mermaidDir.resolve("bpmn-sequence-" + sanitizeId(entry.getKey()) + ".mmd")))) {
                pw.println("sequenceDiagram");
                pw.println("  Note over Engine: Process " + entry.getKey());
                for (BpmnFlow f : entry.getValue()) {
                    pw.println("  Engine->>+" + sanitizeId(f.delegateBean()) + ": " + f.stepName());
                    pw.println("  " + sanitizeId(f.delegateBean()) + "-->>-Engine: done");
                }
            }
        }
    }

    private static String sanitizeId(String s) {
        if (s == null) return "null";
        return s.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private static String mermaidRelationArrow(String relationType) {
        return switch (relationType) {
            case "one-to-many" -> "\"1\" --> \"*\"";
            case "many-to-one" -> "\"*\" --> \"1\"";
            case "one-to-one" -> "\"1\" --> \"1\"";
            case "many-to-many" -> "\"*\" --> \"*\"";
            default -> "-->";
        };
    }

    private static boolean containsLayer(List<ArchitectureInfo> infos, String layer) {
        if (infos == null || infos.isEmpty()) return false;
        return infos.stream().anyMatch(i -> layer.equals(i.layer()));
    }
}
