package fr.geoking.archimo.extract.output;

import fr.geoking.archimo.extract.model.BpmnFlow;
import fr.geoking.archimo.extract.model.ArchitectureInfo;
import fr.geoking.archimo.extract.model.ClassDependency;
import fr.geoking.archimo.extract.model.EndpointFlow;
import fr.geoking.archimo.extract.model.EventFlow;
import fr.geoking.archimo.extract.model.ExtractResult;
import fr.geoking.archimo.extract.model.MessagingFlow;
import fr.geoking.archimo.extract.model.ModuleDependency;
import org.springframework.modulith.core.ApplicationModules;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes Mermaid diagrams for event flows, sequences and module dependencies.
 * Events and commands use different color and shape; command = event type name containing "Command".
 */
public final class MermaidOutput implements DiagramOutput {

    @Override
    public void write(ApplicationModules modules, Path outputDir, ExtractResult result) throws IOException {
        List<EventFlow> flows = result.flows();
        List<ModuleDependency> deps = result.moduleDependencies();
        writeMermaidEventAndCommandFlows(outputDir, flows);
        writeMermaidSequences(outputDir, flows);
        writeMermaidModuleDependencies(outputDir, deps);
        writeArchitectureClassDiagram(outputDir, result.architectureInfos());
        writeComponentDependenciesDiagram(outputDir, result.architectureInfos(), result.classDependencies(), result.fullDependencyMode());
        writeArchitectureFlowDiagram(outputDir, result.architectureInfos());
        writeArchitectureSequenceDiagram(outputDir, result.architectureInfos(), result.classDependencies());
        writeEndpointFlowDiagram(outputDir, result.endpointFlows(), result.classDependencies(), result.architectureInfos());
        writeEndpointSequenceDiagram(outputDir, result.endpointFlows(), result.classDependencies(), result.architectureInfos());
        writeMessagingFlows(outputDir, result.messagingFlows());
        writeBpmnSequences(outputDir, result.bpmnFlows());
    }

    private void writeMermaidEventAndCommandFlows(Path outputDir, List<EventFlow> flows) throws IOException {
        Path mermaidDir = outputDir.resolve("mermaid");
        Files.createDirectories(mermaidDir);
        StringBuilder m = new StringBuilder();
        m.append("%% Events and commands (command = name contains 'Command'); different color and shape\n");
        m.append("flowchart LR\n");
        if (flows == null || flows.isEmpty()) {
            // Keep the diagram renderable in the SPA even when Petclinic (or other
            // apps) doesn't expose Modulith event flows.
            m.append("  noEventFlows[\"No event flows discovered\"]\n");
            Files.writeString(mermaidDir.resolve("event-flows.mmd"), m.toString());
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
                m.append("  ").append(pub).append(" --> ").append(evId).append(" --> ").append(lis).append("\n");
            }
            if (f.listenerModules().isEmpty()) {
                m.append("  ").append(pub).append(" --> ").append(evId).append("\n");
            }
        }
        m.append("  classDef eventNode fill:#fff3e0,stroke:#e65100,stroke-width:2px,rx:8,ry:8\n");
        m.append("  classDef commandNode fill:#e3f2fd,stroke:#1565c0,stroke-width:2px,rx:20,ry:20\n");
        if (!eventNodeIds.isEmpty()) {
            m.append("  class ").append(String.join(",", eventNodeIds)).append(" eventNode\n");
        }
        if (!commandNodeIds.isEmpty()) {
            m.append("  class ").append(String.join(",", commandNodeIds)).append(" commandNode\n");
        }
        Files.writeString(mermaidDir.resolve("event-flows.mmd"), m.toString());
    }

    private void writeMermaidSequences(Path outputDir, List<EventFlow> flows) throws IOException {
        Path dir = outputDir.resolve("mermaid");
        Files.createDirectories(dir);
        for (EventFlow flow : flows) {
            StringBuilder m = new StringBuilder();
            m.append("sequenceDiagram\n");
            String publisherId = sanitizeId(flow.publisherModule());
            m.append("  participant ").append(publisherId).append(" as ").append(flow.publisherModule()).append("\n");
            for (String l : flow.listenerModules()) {
                m.append("  participant ").append(sanitizeId(l)).append(" as ").append(l).append("\n");
            }
            m.append("  ").append(publisherId).append("->>+");
            if (flow.listenerModules().isEmpty()) {
                m.append("?: ").append(flow.eventType()).append("\n");
            } else {
                m.append(sanitizeId(flow.listenerModules().get(0))).append(": ").append(flow.eventType()).append("\n");
                for (int i = 1; i < flow.listenerModules().size(); i++) {
                    m.append("  ").append(sanitizeId(flow.listenerModules().get(i - 1)))
                            .append("->>+").append(sanitizeId(flow.listenerModules().get(i)))
                            .append(": ").append(flow.eventType()).append("\n");
                }
            }
            Files.writeString(dir.resolve("sequence-" + sanitizeId(flow.eventType()) + ".mmd"), m.toString());
        }
    }

    private void writeMermaidModuleDependencies(Path outputDir, List<ModuleDependency> deps) throws IOException {
        Path dir = outputDir.resolve("mermaid");
        Files.createDirectories(dir);
        StringBuilder m = new StringBuilder();
        m.append("%% Module dependencies\n");
        m.append("flowchart LR\n");
        if (deps == null || deps.isEmpty()) {
            // Keep the diagram renderable in the SPA even when no Modulith
            // module relationships are available for the configured dependency
            // kinds.
            m.append("  noModuleDependencies[\"No module dependencies discovered\"]\n");
            Files.writeString(dir.resolve("module-dependencies.mmd"), m.toString());
            return;
        }
        for (ModuleDependency d : deps) {
            m.append("  ").append(sanitizeId(d.fromModule())).append(" --> ").append(sanitizeId(d.toModule())).append("\n");
        }
        Files.writeString(dir.resolve("module-dependencies.mmd"), m.toString());
    }

    private void writeMessagingFlows(Path outputDir, List<MessagingFlow> flows) throws IOException {
        if (flows.isEmpty()) return;
        Path mermaidDir = outputDir.resolve("mermaid");
        Files.createDirectories(mermaidDir);
        StringBuilder m = new StringBuilder();
        m.append("flowchart LR\n");
        for (MessagingFlow f : flows) {
            String pub = sanitizeId(f.publisher());
            String dest = sanitizeId(f.destination());
            m.append("  ").append(pub).append(" -- ").append(f.technology()).append(" --> ").append(dest).append("\n");
            for (String sub : f.subscribers()) {
                m.append("  ").append(dest).append(" --> ").append(sanitizeId(sub)).append("\n");
            }
        }
        Files.writeString(mermaidDir.resolve("messaging-flows.mmd"), m.toString());
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

        StringBuilder m = new StringBuilder();
        m.append("%% Class architecture fallback (non-Modulith)\n");
        m.append("flowchart LR\n");

        for (Map.Entry<String, List<ArchitectureInfo>> entry : byLayer.entrySet()) {
            for (ArchitectureInfo info : entry.getValue()) {
                String id = sanitizeId(info.className());
                String label = simpleName(info.className());
                m.append("  ").append(id).append("[\"").append(label).append("\"]\n");
            }
        }

        for (ArchitectureInfo c : byLayer.getOrDefault("controller", List.of())) {
            for (ArchitectureInfo s : byLayer.getOrDefault("service", List.of())) {
                m.append("  ").append(sanitizeId(c.className())).append(" --> ").append(sanitizeId(s.className())).append("\n");
            }
        }
        for (ArchitectureInfo s : byLayer.getOrDefault("service", List.of())) {
            for (ArchitectureInfo r : byLayer.getOrDefault("repository", List.of())) {
                m.append("  ").append(sanitizeId(s.className())).append(" --> ").append(sanitizeId(r.className())).append("\n");
            }
        }

        m.append("  classDef controller fill:#e3f2fd,stroke:#1565c0,stroke-width:2px\n");
        m.append("  classDef service fill:#e8f5e9,stroke:#2e7d32,stroke-width:2px\n");
        m.append("  classDef repository fill:#fff3e0,stroke:#ef6c00,stroke-width:2px\n");
        applyLayerClass(m, byLayer, "controller");
        applyLayerClass(m, byLayer, "service");
        applyLayerClass(m, byLayer, "repository");

        Files.writeString(mermaidDir.resolve("architecture-class-diagram.mmd"), m.toString());
    }

    private void applyLayerClass(StringBuilder m, Map<String, List<ArchitectureInfo>> byLayer, String layer) {
        List<ArchitectureInfo> infos = byLayer.getOrDefault(layer, List.of());
        if (infos.isEmpty()) return;
        List<String> ids = new ArrayList<>();
        for (ArchitectureInfo info : infos) {
            ids.add(sanitizeId(info.className()));
        }
        m.append("  class ").append(String.join(",", ids)).append(" ").append(layer).append("\n");
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
        StringBuilder m = new StringBuilder();
        m.append("%% Layer flow fallback (non-Modulith)\n");
        m.append("flowchart LR\n");
        appendLayerNode(m, byLayer, "controller", "Controller");
        appendLayerNode(m, byLayer, "service", "Service");
        appendLayerNode(m, byLayer, "repository", "Repository");
        appendLayerNode(m, byLayer, "domain", "Domain");
        appendLayerNode(m, byLayer, "application", "Application");
        appendLayerNode(m, byLayer, "infrastructure", "Infrastructure");
        appendLayerFlowEdges(m, byLayer);
        Files.writeString(mermaidDir.resolve("architecture-flow.mmd"), m.toString());
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

        StringBuilder m = new StringBuilder();
        m.append("%% Request sequence fallback (non-Modulith)\n");
        m.append("sequenceDiagram\n");
        m.append("  participant Client\n");
        m.append("  participant ").append(controller).append("\n");
        m.append("  participant ").append(service).append("\n");
        m.append("  participant ").append(repository).append("\n");
        m.append("  Client->>").append(controller).append(": HTTP request\n");
        m.append("  ").append(controller).append("->>").append(service).append(": invoke use case\n");
        m.append("  ").append(service).append("->>").append(repository).append(": query/persist\n");
        m.append("  ").append(repository).append("-->>").append(service).append(": data\n");
        m.append("  ").append(service).append("-->>").append(controller).append(": response model\n");
        m.append("  ").append(controller).append("-->>Client: HTTP response\n");
        Files.writeString(mermaidDir.resolve("architecture-sequence.mmd"), m.toString());
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

        StringBuilder m = new StringBuilder();
        m.append("%% Component dependencies (from bytecode)\n");
        m.append("flowchart LR\n");
        for (ArchitectureInfo info : infos) {
            String id = sanitizeId(info.className());
            m.append("  ").append(id).append("[\"").append(simpleName(info.className())).append("\"]\n");
        }
        for (ClassDependency dep : classDependencies) {
            if (fullDependencyMode || isInterestingDependency(layerByClass, dep)) {
                m.append("  ").append(sanitizeId(dep.fromClass())).append(" --> ").append(sanitizeId(dep.toClass())).append("\n");
            }
        }
        Files.writeString(mermaidDir.resolve("architecture-component-dependencies.mmd"), m.toString());
    }

    private static Map<String, List<ArchitectureInfo>> groupByLayer(List<ArchitectureInfo> infos) {
        Map<String, List<ArchitectureInfo>> byLayer = new HashMap<>();
        for (ArchitectureInfo info : infos) {
            byLayer.computeIfAbsent(info.layer(), k -> new ArrayList<>()).add(info);
        }
        return byLayer;
    }

    private static void appendLayerNode(StringBuilder m, Map<String, List<ArchitectureInfo>> byLayer, String key, String label) {
        List<ArchitectureInfo> infos = byLayer.get(key);
        if (infos == null || infos.isEmpty()) return;
        m.append("  ").append(label).append("[\"").append(label).append(" (").append(infos.size()).append(" classes)\"]\n");
    }

    private static void appendLayerFlowEdges(StringBuilder m, Map<String, List<ArchitectureInfo>> byLayer) {
        if (byLayer.containsKey("controller") && byLayer.containsKey("service")) {
            m.append("  Controller --> Service\n");
        }
        if (byLayer.containsKey("service") && byLayer.containsKey("repository")) {
            m.append("  Service --> Repository\n");
        }
        if (byLayer.containsKey("service") && byLayer.containsKey("domain")) {
            m.append("  Service --> Domain\n");
        }
        if (byLayer.containsKey("controller") && byLayer.containsKey("application")) {
            m.append("  Controller --> Application\n");
        }
        if (byLayer.containsKey("application") && byLayer.containsKey("infrastructure")) {
            m.append("  Application --> Infrastructure\n");
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

        StringBuilder m = new StringBuilder();
        m.append("%% Endpoint flow map\n");
        m.append("flowchart LR\n");
        m.append("  Client((Client))\n");

        for (EndpointFlow endpoint : endpointFlows) {
            String endpointId = sanitizeId("ep_" + endpoint.httpMethod() + "_" + endpoint.path());
            String controllerId = sanitizeId(endpoint.controllerClass());
            m.append("  ").append(endpointId).append("[\"").append(endpoint.httpMethod()).append(" ").append(endpoint.path()).append("\"]\n");
            m.append("  ").append(controllerId).append("[\"").append(simpleName(endpoint.controllerClass())).append("\"]\n");
            m.append("  Client --> ").append(endpointId).append("\n");
            m.append("  ").append(endpointId).append(" --> ").append(controllerId).append("\n");

            String service = firstDependencyTarget(endpoint.controllerClass(), services, classDependencies);
            if (service != null) {
                String serviceId = sanitizeId(service);
                m.append("  ").append(serviceId).append("[\"").append(simpleName(service)).append("\"]\n");
                m.append("  ").append(controllerId).append(" --> ").append(serviceId).append("\n");
                String repository = firstDependencyTarget(service, repositories, classDependencies);
                if (repository != null) {
                    String repositoryId = sanitizeId(repository);
                    m.append("  ").append(repositoryId).append("[\"").append(simpleName(repository)).append("\"]\n");
                    m.append("  ").append(serviceId).append(" --> ").append(repositoryId).append("\n");
                }
            }
        }
        Files.writeString(mermaidDir.resolve("endpoint-flow.mmd"), m.toString());
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

            StringBuilder m = new StringBuilder();
            m.append("%% Endpoint sequence\n");
            m.append("sequenceDiagram\n");
            m.append("  participant Client\n");
            m.append("  participant ").append(controller).append("\n");
            m.append("  participant ").append(service).append("\n");
            m.append("  participant ").append(repository).append("\n");
            m.append("  Client->>").append(controller).append(": ").append(endpoint.httpMethod()).append(" ").append(endpoint.path()).append("\n");
            m.append("  ").append(controller).append("->>").append(service).append(": ").append(endpoint.controllerMethod()).append("()\n");
            m.append("  ").append(service).append("->>").append(repository).append(": query/persist\n");
            m.append("  ").append(repository).append("-->>").append(service).append(": data\n");
            m.append("  ").append(service).append("-->>").append(controller).append(": response model\n");
            m.append("  ").append(controller).append("-->>Client: HTTP response\n");
            String sequenceFileName = "endpoint-sequence-" + sanitizeId(endpoint.httpMethod() + "_" + endpoint.path() + "_" + endpoint.controllerMethod()) + ".mmd";
            Files.writeString(mermaidDir.resolve(sequenceFileName), m.toString());
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
            StringBuilder m = new StringBuilder();
            m.append("sequenceDiagram\n");
            m.append("  Note over Engine: Process ").append(entry.getKey()).append("\n");
            for (BpmnFlow f : entry.getValue()) {
                m.append("  Engine->>+").append(sanitizeId(f.delegateBean())).append(": ").append(f.stepName()).append("\n");
                m.append("  ").append(sanitizeId(f.delegateBean())).append("-->>-Engine: done\n");
            }
            Files.writeString(mermaidDir.resolve("bpmn-sequence-" + sanitizeId(entry.getKey()) + ".mmd"), m.toString());
        }
    }

    private static String sanitizeId(String s) {
        if (s == null) return "null";
        return s.replaceAll("[^a-zA-Z0-9_]", "_");
    }
}

