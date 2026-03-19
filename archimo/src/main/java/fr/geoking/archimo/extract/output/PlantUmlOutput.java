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
import fr.geoking.archimo.extract.model.MessagingFlow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes PlantUML C4 diagrams and module canvases using Spring Modulith's Documenter.
 * Also generates additional diagrams for classic architecture, messaging and BPMN.
 */
public final class PlantUmlOutput implements DiagramOutput {

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
        writeComponentDependenciesDiagram(outputDir, result.architectureInfos(), result.classDependencies(), result.fullDependencyMode());
        writeArchitectureFlowDiagram(outputDir, result.architectureInfos());
        writeArchitectureSequenceDiagram(outputDir, result.architectureInfos(), result.classDependencies());
        writeEndpointFlowDiagram(outputDir, result.endpointFlows(), result.classDependencies(), result.architectureInfos());
        writeEndpointSequenceDiagram(outputDir, result.endpointFlows(), result.classDependencies(), result.architectureInfos());
        writeMessagingDiagram(outputDir, result.messagingFlows());
        writeBpmnDiagram(outputDir, result.bpmnFlows());
    }

    private void writeArchitectureDiagram(Path outputDir, List<ArchitectureInfo> infos) throws IOException {
        if (infos.isEmpty()) return;
        StringBuilder p = new StringBuilder();
        p.append("@startuml\n!include https://raw.githubusercontent.com/plantuml-office/C4-PlantUML/master/C4_Component.puml\n");
        p.append("LAYOUT_WITH_LEGEND()\n");
        p.append("Title Architecture Layers\n");
        for (ArchitectureInfo info : infos) {
            String shortName = info.className().substring(info.className().lastIndexOf('.') + 1);
            p.append("Component(").append(shortName).append(", \"").append(shortName).append("\", \"").append(info.layer()).append("\")\n");
        }
        p.append("@enduml");
        Files.writeString(outputDir.resolve("architecture-layers.puml"), p.toString());
    }

    /**
     * Fallback class-level architecture diagram for non-Modulith projects.
     * It groups scanned classes by layer and always exposes a readable
     * controller -> service -> repository flow when those layers exist.
     */
    private void writeArchitectureClassDiagram(Path outputDir, List<ArchitectureInfo> infos) throws IOException {
        if (infos.isEmpty()) return;

        Map<String, List<ArchitectureInfo>> byLayer = new LinkedHashMap<>();
        for (ArchitectureInfo info : infos) {
            byLayer.computeIfAbsent(info.layer(), k -> new ArrayList<>()).add(info);
        }

        StringBuilder p = new StringBuilder();
        p.append("@startuml\n");
        p.append("title Class Architecture (Controller-Service-Repository)\n");
        p.append("skinparam packageStyle rectangle\n");
        p.append("hide empty members\n");

        for (Map.Entry<String, List<ArchitectureInfo>> layerEntry : byLayer.entrySet()) {
            String layer = layerEntry.getKey();
            p.append("package \"").append(capitalize(layer)).append("\" {\n");
            for (ArchitectureInfo info : layerEntry.getValue()) {
                p.append("  class ").append(toId(info.className()))
                        .append(" as \"").append(simpleName(info.className())).append("\"\n");
            }
            p.append("}\n");
        }

        // Provide readable architectural flow, even if concrete type dependencies
        // are not extracted from bytecode yet.
        if (byLayer.containsKey("controller") && byLayer.containsKey("service")) {
            p.append("Controller ..> Service : uses\n");
        }
        if (byLayer.containsKey("service") && byLayer.containsKey("repository")) {
            p.append("Service ..> Repository : uses\n");
        }
        if (byLayer.containsKey("service") && byLayer.containsKey("domain")) {
            p.append("Service ..> Domain : manipulates\n");
        }
        if (byLayer.containsKey("controller") && byLayer.containsKey("application")) {
            p.append("Controller ..> Application : orchestrates\n");
        }
        if (byLayer.containsKey("application") && byLayer.containsKey("infrastructure")) {
            p.append("Application ..> Infrastructure : delegates\n");
        }

        p.append("@enduml");
        Files.writeString(outputDir.resolve("architecture-class-diagram.puml"), p.toString());
    }

    private void writeArchitectureFlowDiagram(Path outputDir, List<ArchitectureInfo> infos) throws IOException {
        if (infos.isEmpty()) return;
        Map<String, List<ArchitectureInfo>> byLayer = groupByLayer(infos);

        StringBuilder p = new StringBuilder();
        p.append("@startuml\n");
        p.append("title Architecture Flow (Layered)\n");
        p.append("left to right direction\n");
        appendLayerNode(p, byLayer, "controller", "Controller");
        appendLayerNode(p, byLayer, "service", "Service");
        appendLayerNode(p, byLayer, "repository", "Repository");
        appendLayerNode(p, byLayer, "domain", "Domain");
        appendLayerNode(p, byLayer, "application", "Application");
        appendLayerNode(p, byLayer, "infrastructure", "Infrastructure");
        appendLayerFlowEdges(p, byLayer);
        p.append("@enduml");
        Files.writeString(outputDir.resolve("architecture-flow.puml"), p.toString());
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

        StringBuilder p = new StringBuilder();
        p.append("@startuml\n");
        p.append("title Request Sequence (Controller -> Service -> Repository)\n");
        p.append("actor Client\n");
        p.append("participant ").append(controller).append("\n");
        p.append("participant ").append(service).append("\n");
        p.append("participant ").append(repository).append("\n");
        p.append("Client -> ").append(controller).append(" : HTTP request\n");
        p.append(controller).append(" -> ").append(service).append(" : invoke use case\n");
        p.append(service).append(" -> ").append(repository).append(" : query/persist\n");
        p.append(repository).append(" --> ").append(service).append(" : data\n");
        p.append(service).append(" --> ").append(controller).append(" : response model\n");
        p.append(controller).append(" --> Client : HTTP response\n");
        p.append("@enduml");
        Files.writeString(outputDir.resolve("architecture-sequence.puml"), p.toString());
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

        StringBuilder p = new StringBuilder();
        p.append("@startuml\n");
        p.append("title Component Dependencies (from bytecode)\n");
        p.append("skinparam packageStyle rectangle\n");
        p.append("hide empty members\n");
        for (ArchitectureInfo info : infos) {
            String id = toId(info.className());
            p.append("class ").append(id).append(" as \"").append(simpleName(info.className())).append("\"\n");
        }
        for (ClassDependency dep : classDependencies) {
            if (fullDependencyMode || isInterestingDependency(layerByClass, dep)) {
                p.append(toId(dep.fromClass())).append(" --> ").append(toId(dep.toClass())).append("\n");
            }
        }
        p.append("@enduml");
        Files.writeString(outputDir.resolve("architecture-component-dependencies.puml"), p.toString());
    }

    private static Map<String, List<ArchitectureInfo>> groupByLayer(List<ArchitectureInfo> infos) {
        Map<String, List<ArchitectureInfo>> byLayer = new LinkedHashMap<>();
        for (ArchitectureInfo info : infos) {
            byLayer.computeIfAbsent(info.layer(), k -> new ArrayList<>()).add(info);
        }
        return byLayer;
    }

    private static void appendLayerNode(StringBuilder p, Map<String, List<ArchitectureInfo>> byLayer, String key, String label) {
        List<ArchitectureInfo> infos = byLayer.get(key);
        if (infos == null || infos.isEmpty()) return;
        p.append("rectangle \"").append(label).append("\\n(").append(infos.size()).append(" classes)\" as ").append(label).append("\n");
    }

    private static void appendLayerFlowEdges(StringBuilder p, Map<String, List<ArchitectureInfo>> byLayer) {
        if (byLayer.containsKey("controller") && byLayer.containsKey("service")) {
            p.append("Controller --> Service : uses\n");
        }
        if (byLayer.containsKey("service") && byLayer.containsKey("repository")) {
            p.append("Service --> Repository : uses\n");
        }
        if (byLayer.containsKey("service") && byLayer.containsKey("domain")) {
            p.append("Service --> Domain : manipulates\n");
        }
        if (byLayer.containsKey("controller") && byLayer.containsKey("application")) {
            p.append("Controller --> Application : orchestrates\n");
        }
        if (byLayer.containsKey("application") && byLayer.containsKey("infrastructure")) {
            p.append("Application --> Infrastructure : delegates\n");
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

        StringBuilder p = new StringBuilder();
        p.append("@startuml\n");
        p.append("title Endpoint Flow Map\n");
        p.append("left to right direction\n");
        p.append("actor Client\n");

        for (EndpointFlow endpoint : endpointFlows) {
            String endpointId = "ep_" + toId(endpoint.httpMethod() + "_" + endpoint.path());
            String controllerId = toId(endpoint.controllerClass());
            p.append("usecase \"").append(endpoint.httpMethod()).append(" ").append(endpoint.path()).append("\" as ").append(endpointId).append("\n");
            p.append("component \"").append(simpleName(endpoint.controllerClass())).append("\" as ").append(controllerId).append("\n");
            p.append("Client --> ").append(endpointId).append("\n");
            p.append(endpointId).append(" --> ").append(controllerId).append("\n");

            String service = firstDependencyTarget(endpoint.controllerClass(), services, classDependencies);
            if (service != null) {
                String serviceId = toId(service);
                p.append("component \"").append(simpleName(service)).append("\" as ").append(serviceId).append("\n");
                p.append(controllerId).append(" --> ").append(serviceId).append("\n");

                String repository = firstDependencyTarget(service, repositories, classDependencies);
                if (repository != null) {
                    String repoId = toId(repository);
                    p.append("component \"").append(simpleName(repository)).append("\" as ").append(repoId).append("\n");
                    p.append(serviceId).append(" --> ").append(repoId).append("\n");
                }
            }
        }
        p.append("@enduml");
        Files.writeString(outputDir.resolve("endpoint-flow.puml"), p.toString());
    }

    private void writeEndpointSequenceDiagram(Path outputDir,
                                              List<EndpointFlow> endpointFlows,
                                              List<ClassDependency> classDependencies,
                                              List<ArchitectureInfo> architectureInfos) throws IOException {
        if (endpointFlows == null || endpointFlows.isEmpty()) return;
        EndpointFlow endpoint = endpointFlows.get(0);
        Map<String, List<ArchitectureInfo>> byLayer = groupByLayer(architectureInfos);
        String controllerClass = endpoint.controllerClass();
        String serviceClass = firstDependencyTarget(controllerClass, byLayer.getOrDefault("service", List.of()), classDependencies);
        String repositoryClass = serviceClass == null ? null
                : firstDependencyTarget(serviceClass, byLayer.getOrDefault("repository", List.of()), classDependencies);

        String controller = simpleName(controllerClass);
        String service = serviceClass != null ? simpleName(serviceClass) : "Service";
        String repository = repositoryClass != null ? simpleName(repositoryClass) : "Repository";

        StringBuilder p = new StringBuilder();
        p.append("@startuml\n");
        p.append("title Endpoint Sequence - ").append(endpoint.httpMethod()).append(" ").append(endpoint.path()).append("\n");
        p.append("actor Client\n");
        p.append("participant ").append(controller).append("\n");
        p.append("participant ").append(service).append("\n");
        p.append("participant ").append(repository).append("\n");
        p.append("Client -> ").append(controller).append(" : ").append(endpoint.httpMethod()).append(" ").append(endpoint.path()).append("\n");
        p.append(controller).append(" -> ").append(service).append(" : ").append(endpoint.controllerMethod()).append("()\n");
        p.append(service).append(" -> ").append(repository).append(" : query/persist\n");
        p.append(repository).append(" --> ").append(service).append(" : data\n");
        p.append(service).append(" --> ").append(controller).append(" : response model\n");
        p.append(controller).append(" --> Client : HTTP response\n");
        p.append("@enduml");
        Files.writeString(outputDir.resolve("endpoint-sequence.puml"), p.toString());
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

    private void writeMessagingDiagram(Path outputDir, List<MessagingFlow> flows) throws IOException {
        if (flows.isEmpty()) return;
        StringBuilder p = new StringBuilder();
        p.append("@startuml\n!include https://raw.githubusercontent.com/plantuml-office/C4-PlantUML/master/C4_Container.puml\n");
        p.append("Title Messaging Flows\n");
        for (MessagingFlow flow : flows) {
            p.append("System_Ext(").append(flow.destination().replaceAll("[^a-zA-Z0-9]", "_")).append(", \"").append(flow.destination()).append("\", \"").append(flow.technology()).append("\")\n");
        }
        p.append("@enduml");
        Files.writeString(outputDir.resolve("messaging-flows.puml"), p.toString());
    }

    private void writeBpmnDiagram(Path outputDir, List<BpmnFlow> flows) throws IOException {
        if (flows.isEmpty()) return;
        StringBuilder p = new StringBuilder();
        p.append("@startuml\nTitle BPMN Flows\n");
        for (BpmnFlow flow : flows) {
            p.append("node \"").append(flow.processId()).append("\" {\n");
            p.append("  [").append(flow.stepName()).append("] <<").append(flow.delegateBean()).append(">>\n");
            p.append("}\n");
        }
        p.append("@enduml");
        Files.writeString(outputDir.resolve("bpmn-flows.puml"), p.toString());
    }
}

