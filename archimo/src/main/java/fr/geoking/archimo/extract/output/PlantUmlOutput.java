package fr.geoking.archimo.extract.output;

import fr.geoking.archimo.extract.model.ExtractResult;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;
import org.springframework.modulith.docs.Documenter.DiagramOptions;
import org.springframework.modulith.docs.Documenter.Options;

import fr.geoking.archimo.extract.model.ArchitectureInfo;
import fr.geoking.archimo.extract.model.BpmnFlow;
import fr.geoking.archimo.extract.model.ClassDependency;
import fr.geoking.archimo.extract.model.EndpointFlow;
import fr.geoking.archimo.extract.model.EntityRelation;
import fr.geoking.archimo.extract.model.MessagingFlow;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Writes PlantUML C4 diagrams and module canvases using Spring Modulith's Documenter.
 * Also generates additional diagrams for classic architecture, messaging and BPMN.
 */
public final class PlantUmlOutput implements DiagramOutput {

    /** Per-endpoint sequence + data-lineage files scale with HTTP mappings, not application class count. */
    private static final int MAX_ENDPOINT_SPECIFIC_DIAGRAMS = 80;

    @Override
    public void write(ApplicationModules modules, Path outputDir, ExtractResult result) throws IOException {
        if (modules != null) {
            Options docOptions = Options.defaults().withOutputFolder(outputDir.toAbsolutePath().toString());
            Documenter documenter = new Documenter(modules, docOptions);
            documenter
                    .writeModulesAsPlantUml(DiagramOptions.defaults().withStyle(DiagramOptions.DiagramStyle.C4))
                    .writeIndividualModulesAsPlantUml(DiagramOptions.defaults().withStyle(DiagramOptions.DiagramStyle.C4))
                    .writeModuleCanvases();
        }

        writeArchitectureDiagram(outputDir, result.architectureInfos());
        writeArchitectureClassDiagram(outputDir, result.architectureInfos());
        writeEntityRelationshipDiagram(outputDir, result.entityRelations());
        writeDeploymentDiagram(outputDir, result.architectureInfos(), result.endpointFlows(), result.messagingFlows(), result.entityRelations());
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
        writeMessagingDiagram(outputDir, result.messagingFlows());
        writeBpmnDiagram(outputDir, result.bpmnFlows());
    }

    private void writeArchitectureDiagram(Path outputDir, List<ArchitectureInfo> infos) throws IOException {
        if (infos.isEmpty()) return;
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(outputDir.resolve("architecture-layers.puml")))) {
            pw.println("@startuml");
            pw.println("!include https://raw.githubusercontent.com/plantuml-office/C4-PlantUML/master/C4_Component.puml");
            pw.println("LAYOUT_WITH_LEGEND()");
            pw.println("Title Architecture Layers");
            infos.stream().forEach(info -> {
                String shortName = info.className().substring(info.className().lastIndexOf('.') + 1);
                pw.println("Component(" + shortName + ", \"" + shortName + "\", \"" + info.layer() + "\")");
            });
            pw.println("@enduml");
        }
    }

    /**
     * Fallback class-level architecture diagram for non-Modulith projects.
     * It groups scanned classes by layer and exposes a readable
     * controller -> service -> repository flow when those layers exist.
     */
    private void writeArchitectureClassDiagram(Path outputDir, List<ArchitectureInfo> infos) throws IOException {
        if (infos.isEmpty()) return;

        Map<String, List<ArchitectureInfo>> byLayer = groupByLayer(infos);

        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(outputDir.resolve("architecture-class-diagram.puml")))) {
            pw.println("@startuml");
            pw.println("title Class Architecture (Controller-Service-Repository)");
            pw.println("skinparam packageStyle rectangle");
            pw.println("hide empty members");

            for (Map.Entry<String, List<ArchitectureInfo>> layerEntry : byLayer.entrySet()) {
                String layer = layerEntry.getKey();
                pw.println("package \"" + capitalize(layer) + "\" {");
                layerEntry.getValue().forEach(info -> {
                    pw.println("  class " + toId(info.className()) + " as \"" + simpleName(info.className()) + "\"");
                });
                pw.println("}");
            }

            // Provide readable architectural flow, even if concrete type dependencies
            // are not extracted from bytecode yet.
            if (byLayer.containsKey("controller") && byLayer.containsKey("service")) {
                pw.println("Controller ..> Service : uses");
            }
            if (byLayer.containsKey("service") && byLayer.containsKey("repository")) {
                pw.println("Service ..> Repository : uses");
            }
            if (byLayer.containsKey("service") && byLayer.containsKey("domain")) {
                pw.println("Service ..> Domain : manipulates");
            }
            if (byLayer.containsKey("controller") && byLayer.containsKey("application")) {
                pw.println("Controller ..> Application : orchestrates");
            }
            if (byLayer.containsKey("application") && byLayer.containsKey("infrastructure")) {
                pw.println("Application ..> Infrastructure : delegates");
            }

            pw.println("@enduml");
        }
    }

    private void writeArchitectureFlowDiagram(Path outputDir, List<ArchitectureInfo> infos) throws IOException {
        if (infos.isEmpty()) return;
        Map<String, List<ArchitectureInfo>> byLayer = groupByLayer(infos);

        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(outputDir.resolve("architecture-flow.puml")))) {
            pw.println("@startuml");
            pw.println("title Architecture Flow (Layered)");
            pw.println("left to right direction");
            appendLayerNode(pw, byLayer, "controller", "Controller");
            appendLayerNode(pw, byLayer, "service", "Service");
            appendLayerNode(pw, byLayer, "repository", "Repository");
            appendLayerNode(pw, byLayer, "domain", "Domain");
            appendLayerNode(pw, byLayer, "application", "Application");
            appendLayerNode(pw, byLayer, "infrastructure", "Infrastructure");
            appendLayerFlowEdges(pw, byLayer);
            pw.println("@enduml");
        }
    }

    private void writeEntityRelationshipDiagram(Path outputDir, List<EntityRelation> relations) throws IOException {
        if (relations == null || relations.isEmpty()) return;
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(outputDir.resolve("entity-relationship.puml")))) {
            pw.println("@startuml");
            pw.println("title Entity Relationship Diagram");
            pw.println("hide empty members");

            relations.stream()
                    .flatMap(r -> Stream.of(r.fromEntity(), r.toEntity()))
                    .distinct()
                    .forEach(e -> pw.println("class " + toId(e) + " as \"" + simpleName(e) + "\" <<Entity>>"));

            relations.stream()
                    .forEach(r -> pw.println(toId(r.fromEntity()) + " " + relationArrow(r.relationType()) + " " + toId(r.toEntity()) + " : " + r.relationType()));

            pw.println("@enduml");
        }
    }

    private void writeDeploymentDiagram(Path outputDir,
                                        List<ArchitectureInfo> infos,
                                        List<EndpointFlow> endpointFlows,
                                        List<MessagingFlow> messagingFlows,
                                        List<EntityRelation> entityRelations) throws IOException {
        boolean hasApi = (endpointFlows != null && !endpointFlows.isEmpty()) || containsLayer(infos, "controller");
        boolean hasDb = containsLayer(infos, "repository") || (entityRelations != null && !entityRelations.isEmpty());
        boolean hasMessaging = messagingFlows != null && !messagingFlows.isEmpty();
        if (!hasApi && !hasDb && !hasMessaging) return;

        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(outputDir.resolve("deployment-diagram.puml")))) {
            pw.println("@startuml");
            pw.println("!include https://raw.githubusercontent.com/plantuml-office/C4-PlantUML/master/C4_Deployment.puml");
            pw.println("title Runtime Deployment View");
            pw.println("Deployment_Node(browser, \"Client\", \"Web browser\") {");
            pw.println("}");
            pw.println("Deployment_Node(platform, \"Runtime Platform\", \"JVM / Spring Boot\") {");
            pw.println("  Container(app, \"Application\", \"Spring Boot\")");
            pw.println("}");
            if (hasApi) {
                pw.println("Rel(browser, app, \"HTTP\")");
            }
            if (hasDb) {
                pw.println("Deployment_Node(dbnode, \"Database Node\", \"Relational DB\") {");
                pw.println("  ContainerDb(db, \"Application Database\", \"SQL\")");
                pw.println("}");
                pw.println("Rel(app, db, \"JPA / SQL\")");
            }
            if (hasMessaging) {
                pw.println("Deployment_Node(msgnode, \"Messaging\", \"Broker\") {");
                pw.println("  ContainerQueue(broker, \"Message Broker\", \"Kafka/JMS\")");
                pw.println("}");
                pw.println("Rel(app, broker, \"Publish/Consume\")");
            }
            pw.println("@enduml");
        }
    }

    private void writeDataLineageDiagram(Path outputDir,
                                          List<EndpointFlow> endpointFlows,
                                          List<ArchitectureInfo> infos,
                                          List<ClassDependency> classDependencies,
                                          List<EntityRelation> entityRelations) throws IOException {
        if (endpointFlows == null || endpointFlows.isEmpty()) return;
        boolean hasEntities = entityRelations != null && !entityRelations.isEmpty();
        if (!hasEntities && (infos == null || infos.isEmpty())) return;

        List<ArchitectureInfo> safeInfos = infos == null ? List.of() : infos;
        List<ClassDependency> safeDeps = classDependencies == null ? List.of() : classDependencies;

        // Extract repository and entity types from extracted artifacts.
        List<ArchitectureInfo> repositories = safeInfos.stream().filter(i -> "repository".equals(i.layer())).toList();
        List<ArchitectureInfo> services = safeInfos.stream().filter(i -> "service".equals(i.layer())).toList();
        List<ArchitectureInfo> controllers = safeInfos.stream().filter(i -> "controller".equals(i.layer())).toList();
        java.util.Set<String> entityTypes = new java.util.LinkedHashSet<>();
        if (entityRelations != null) {
            for (EntityRelation r : entityRelations) {
                entityTypes.add(r.fromEntity());
                entityTypes.add(r.toEntity());
            }
        }

        if (repositories.isEmpty() && services.isEmpty() && controllers.isEmpty()) return;

        Map<String, List<String>> repoToEntities = new LinkedHashMap<>();
        for (ClassDependency dep : safeDeps) {
            if (entityTypes.contains(dep.toClass()) && repositories.stream().anyMatch(r -> r.className().equals(dep.fromClass()))) {
                repoToEntities.computeIfAbsent(dep.fromClass(), k -> new ArrayList<>()).add(dep.toClass());
            }
        }

        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(outputDir.resolve("data-lineage-diagram.puml")))) {
            pw.println("@startuml");
            pw.println("title Data Lineage (Endpoint -> Entities)");
            pw.println("actor Client");

            // Keep node declarations unique to avoid overly large diagrams.
            java.util.Set<String> declaredNodes = new java.util.LinkedHashSet<>();
            for (EndpointFlow endpoint : endpointFlows) {
                String endpointId = "ep_" + toId(endpoint.httpMethod() + "_" + endpoint.path() + "_" + endpoint.controllerMethod());
                String controllerId = toId(endpoint.controllerClass());

                String serviceClass = firstDependencyTarget(endpoint.controllerClass(), services, safeDeps);
                String repositoryClass = serviceClass == null ? null : firstDependencyTarget(serviceClass, repositories, safeDeps);

                pw.println("rectangle \"" + endpoint.httpMethod() + " " + endpoint.path() + "\" as " + endpointId);

                if (declaredNodes.add(controllerId)) {
                    pw.println("component \"" + simpleName(endpoint.controllerClass()) + "\" as " + controllerId);
                }
                pw.println("Client --> " + endpointId);
                pw.println(endpointId + " --> " + controllerId);

                if (serviceClass != null) {
                    String serviceId = toId(serviceClass);
                    if (declaredNodes.add(serviceId)) {
                        pw.println("component \"" + simpleName(serviceClass) + "\" as " + serviceId);
                    }
                    pw.println(controllerId + " --> " + serviceId);

                    if (repositoryClass != null) {
                        String repoId = toId(repositoryClass);
                        if (declaredNodes.add(repoId)) {
                            pw.println("component \"" + simpleName(repositoryClass) + "\" as " + repoId);
                        }
                        pw.println(serviceId + " --> " + repoId);

                        // Link repository to discovered entities (heuristic via bytecode dependency).
                        List<String> entities = repoToEntities.getOrDefault(repositoryClass, List.of());
                        if (!entities.isEmpty()) {
                            for (String entity : entities) {
                                String entityId = toId(entity);
                                if (declaredNodes.add(entityId)) {
                                    pw.println("class " + entityId + " as \"" + simpleName(entity) + "\" <<Entity>>");
                                }
                                pw.println(repoId + " --> " + entityId + " : query/persist");
                            }
                        }
                    }
                }
            }
            pw.println("@enduml");
        }
    }

    private void writeEndpointDataLineageDiagram(Path outputDir,
                                                  List<EndpointFlow> endpointFlows,
                                                  List<ArchitectureInfo> infos,
                                                  List<ClassDependency> classDependencies,
                                                  List<EntityRelation> entityRelations) throws IOException {
        if (endpointFlows == null || endpointFlows.isEmpty()) return;

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

        // Repository -> entities heuristic via bytecode
        Map<String, List<String>> repoToEntities = new LinkedHashMap<>();
        for (ClassDependency dep : safeDeps) {
            if (entityTypes.contains(dep.toClass()) && repositories.stream().anyMatch(r -> r.className().equals(dep.fromClass()))) {
                repoToEntities.computeIfAbsent(dep.fromClass(), k -> new ArrayList<>()).add(dep.toClass());
            }
        }

        for (EndpointFlow endpoint : endpointFlows) {
            String controllerClass = endpoint.controllerClass();
            String serviceClass = firstDependencyTarget(controllerClass, services, safeDeps);
            String repositoryClass = serviceClass == null ? null : firstDependencyTarget(serviceClass, repositories, safeDeps);

            String endpointId = "ep_" + toId(endpoint.httpMethod() + "_" + endpoint.path() + "_" + endpoint.controllerMethod());
            String controllerId = toId(controllerClass);
            String serviceId = serviceClass != null ? toId(serviceClass) : null;
            String repoId = repositoryClass != null ? toId(repositoryClass) : null;

            String sequenceFileName = "endpoint-data-lineage-" + toId(endpoint.httpMethod() + "_" + endpoint.path() + "_" + endpoint.controllerMethod()) + ".puml";
            try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(outputDir.resolve(sequenceFileName)))) {
                pw.println("@startuml");
                pw.println("title Endpoint Data Lineage - " + endpoint.httpMethod() + " " + endpoint.path());
                pw.println("hide empty members");

                pw.println("actor Client");
                pw.println("rectangle \"" + endpoint.httpMethod() + " " + endpoint.path() + "\" as " + endpointId);
                pw.println("component \"" + simpleName(controllerClass) + "\" as " + controllerId);
                pw.println("Client --> " + endpointId);
                pw.println(endpointId + " --> " + controllerId);

                if (serviceClass != null) {
                    pw.println("component \"" + simpleName(serviceClass) + "\" as " + serviceId);
                    pw.println(controllerId + " --> " + serviceId);
                }
                if (repositoryClass != null) {
                    pw.println("component \"" + simpleName(repositoryClass) + "\" as " + repoId);
                    pw.println((serviceId != null ? serviceId : controllerId) + " --> " + repoId);

                    List<String> entities = repoToEntities.getOrDefault(repositoryClass, List.of());
                    if (!entities.isEmpty()) {
                        for (String entity : entities) {
                            String entityId = toId(entity);
                            pw.println("class " + entityId + " as \"" + simpleName(entity) + "\" <<Entity>>");
                            pw.println(repoId + " --> " + entityId + " : query/persist");
                        }
                    } else {
                        pw.println("note \"No entity lineage discovered\" as noEntity");
                        pw.println(repoId + " ..> noEntity");
                    }
                } else {
                    pw.println("note \"No repository discovered\" as noRepo");
                    pw.println(controllerId + " ..> noRepo");
                }

                pw.println("@enduml");
            }
        }
    }

    private void writeArchitectureSequenceDiagram(Path outputDir, List<ArchitectureInfo> infos, List<ClassDependency> classDependencies) throws IOException {
        if (infos.isEmpty()) return;
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

        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(outputDir.resolve("architecture-sequence.puml")))) {
            pw.println("@startuml");
            pw.println("title Request Sequence (Controller -> Service -> Repository)");
            pw.println("actor Client");
            pw.println("participant " + controller);
            pw.println("participant " + service);
            pw.println("participant " + repository);
            pw.println("Client -> " + controller + " : HTTP request");
            pw.println(controller + " -> " + service + " : invoke use case");
            pw.println(service + " -> " + repository + " : query/persist");
            pw.println(repository + " --> " + service + " : data");
            pw.println(service + " --> " + controller + " : response model");
            pw.println(controller + " --> Client : HTTP response");
            pw.println("@enduml");
        }
    }

    private void writeComponentDependenciesDiagram(Path outputDir,
                                                   List<ArchitectureInfo> infos,
                                                   List<ClassDependency> classDependencies,
                                                   boolean fullDependencyMode) throws IOException {
        if (infos.isEmpty() || classDependencies == null || classDependencies.isEmpty()) return;

        Map<String, String> layerByClass = new LinkedHashMap<>();
        for (ArchitectureInfo info : infos) {
            layerByClass.put(info.className(), info.layer());
        }

        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(outputDir.resolve("architecture-component-dependencies.puml")))) {
            pw.println("@startuml");
            pw.println("title Component Dependencies (from bytecode)");
            pw.println("skinparam packageStyle rectangle");
            pw.println("hide empty members");
            for (ArchitectureInfo info : infos) {
                String id = toId(info.className());
                pw.println("class " + id + " as \"" + simpleName(info.className()) + "\"");
            }
            for (ClassDependency dep : classDependencies) {
                if (fullDependencyMode || isInterestingDependency(layerByClass, dep)) {
                    pw.println(toId(dep.fromClass()) + " --> " + toId(dep.toClass()));
                }
            }
            pw.println("@enduml");
        }
    }

    private static Map<String, List<ArchitectureInfo>> groupByLayer(List<ArchitectureInfo> infos) {
        Map<String, List<ArchitectureInfo>> byLayer = new LinkedHashMap<>();
        for (ArchitectureInfo info : infos) {
            byLayer.computeIfAbsent(info.layer(), k -> new ArrayList<>()).add(info);
        }
        return byLayer;
    }

    private static void appendLayerNode(PrintWriter pw, Map<String, List<ArchitectureInfo>> byLayer, String key, String label) {
        List<ArchitectureInfo> infos = byLayer.get(key);
        if (infos == null || infos.isEmpty()) return;
        pw.println("rectangle \"" + label + "\\n(" + infos.size() + " classes)\" as " + label);
    }

    private static void appendLayerFlowEdges(PrintWriter pw, Map<String, List<ArchitectureInfo>> byLayer) {
        if (byLayer.containsKey("controller") && byLayer.containsKey("service")) {
            pw.println("Controller --> Service : uses");
        }
        if (byLayer.containsKey("service") && byLayer.containsKey("repository")) {
            pw.println("Service --> Repository : uses");
        }
        if (byLayer.containsKey("service") && byLayer.containsKey("domain")) {
            pw.println("Service --> Domain : manipulates");
        }
        if (byLayer.containsKey("controller") && byLayer.containsKey("application")) {
            pw.println("Controller --> Application : orchestrates");
        }
        if (byLayer.containsKey("application") && byLayer.containsKey("infrastructure")) {
            pw.println("Application --> Infrastructure : delegates");
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
        Map<String, List<ArchitectureInfo>> byLayer = groupByLayer(architectureInfos);
        List<ArchitectureInfo> services = byLayer.getOrDefault("service", List.of());
        List<ArchitectureInfo> repositories = byLayer.getOrDefault("repository", List.of());

        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(outputDir.resolve("endpoint-flow.puml")))) {
            pw.println("@startuml");
            pw.println("title Endpoint Flow Map");
            pw.println("left to right direction");
            pw.println("actor Client");

            for (EndpointFlow endpoint : endpointFlows) {
                String endpointId = "ep_" + toId(endpoint.httpMethod() + "_" + endpoint.path());
                String controllerId = toId(endpoint.controllerClass());
                pw.println("usecase \"" + endpoint.httpMethod() + " " + endpoint.path() + "\" as " + endpointId);
                pw.println("component \"" + simpleName(endpoint.controllerClass()) + "\" as " + controllerId);
                pw.println("Client --> " + endpointId);
                pw.println(endpointId + " --> " + controllerId);

                String service = firstDependencyTarget(endpoint.controllerClass(), services, classDependencies);
                if (service != null) {
                    String serviceId = toId(service);
                    pw.println("component \"" + simpleName(service) + "\" as " + serviceId);
                    pw.println(controllerId + " --> " + serviceId);

                    String repository = firstDependencyTarget(service, repositories, classDependencies);
                    if (repository != null) {
                        String repoId = toId(repository);
                        pw.println("component \"" + simpleName(repository) + "\" as " + repoId);
                        pw.println(serviceId + " --> " + repoId);
                    }
                }
            }
            pw.println("@enduml");
        }
    }

    private void writeEndpointSequenceDiagram(Path outputDir,
                                              List<EndpointFlow> endpointFlows,
                                              List<ClassDependency> classDependencies,
                                              List<ArchitectureInfo> architectureInfos) throws IOException {
        if (endpointFlows == null || endpointFlows.isEmpty()) return;
        Map<String, List<ArchitectureInfo>> byLayer = groupByLayer(architectureInfos);
        for (EndpointFlow endpoint : endpointFlows) {
            String controllerClass = endpoint.controllerClass();
            String serviceClass = firstDependencyTarget(controllerClass, byLayer.getOrDefault("service", List.of()), classDependencies);
            String repositoryClass = serviceClass == null ? null
                    : firstDependencyTarget(serviceClass, byLayer.getOrDefault("repository", List.of()), classDependencies);

            String controller = simpleName(controllerClass);
            String service = serviceClass != null ? simpleName(serviceClass) : "Service";
            String repository = repositoryClass != null ? simpleName(repositoryClass) : "Repository";

            String sequenceFileName = "endpoint-sequence-" + toId(endpoint.httpMethod() + "_" + endpoint.path() + "_" + endpoint.controllerMethod()) + ".puml";
            try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(outputDir.resolve(sequenceFileName)))) {
                pw.println("@startuml");
                pw.println("title Endpoint Sequence - " + endpoint.httpMethod() + " " + endpoint.path());
                pw.println("actor Client");
                pw.println("participant " + controller);
                pw.println("participant " + service);
                pw.println("participant " + repository);
                pw.println("Client -> " + controller + " : " + endpoint.httpMethod() + " " + endpoint.path());
                pw.println(controller + " -> " + service + " : " + endpoint.controllerMethod() + "()");
                pw.println(service + " -> " + repository + " : query/persist");
                pw.println(repository + " --> " + service + " : data");
                pw.println(service + " --> " + controller + " : response model");
                pw.println(controller + " --> Client : HTTP response");
                pw.println("@enduml");
            }
        }
    }

    private static String simpleName(String fqcn) {
        int idx = fqcn.lastIndexOf('.');
        return idx >= 0 ? fqcn.substring(idx + 1) : fqcn;
    }

    private static String toId(String fqcn) {
        return fqcn.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private static String capitalize(String value) {
        if (value == null || value.isBlank()) return value;
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static boolean containsLayer(List<ArchitectureInfo> infos, String layer) {
        if (infos == null || infos.isEmpty()) return false;
        return infos.stream().anyMatch(i -> layer.equals(i.layer()));
    }

    private static String relationArrow(String relationType) {
        return switch (relationType) {
            case "one-to-many" -> "\"1\" --> \"*\"";
            case "many-to-one" -> "\"*\" --> \"1\"";
            case "one-to-one" -> "\"1\" --> \"1\"";
            case "many-to-many" -> "\"*\" --> \"*\"";
            default -> "-->";
        };
    }

    private void writeMessagingDiagram(Path outputDir, List<MessagingFlow> flows) throws IOException {
        if (flows.isEmpty()) return;
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(outputDir.resolve("messaging-flows.puml")))) {
            pw.println("@startuml");
            pw.println("!include https://raw.githubusercontent.com/plantuml-office/C4-PlantUML/master/C4_Container.puml");
            pw.println("Title Messaging Flows");
            for (MessagingFlow flow : flows) {
                pw.println("System_Ext(" + flow.destination().replaceAll("[^a-zA-Z0-9]", "_") + ", \"" + flow.destination() + "\", \"" + flow.technology() + "\")");
            }
            pw.println("@enduml");
        }
    }

    private void writeBpmnDiagram(Path outputDir, List<BpmnFlow> flows) throws IOException {
        if (flows.isEmpty()) return;
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(outputDir.resolve("bpmn-flows.puml")))) {
            pw.println("@startuml");
            pw.println("Title BPMN Flows");
            for (BpmnFlow flow : flows) {
                pw.println("node \"" + flow.processId() + "\" {");
                pw.println("  [" + flow.stepName() + "] <<" + flow.delegateBean() + ">>");
                pw.println("}");
            }
            pw.println("@enduml");
        }
    }
}
