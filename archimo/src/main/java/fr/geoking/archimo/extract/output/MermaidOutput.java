package fr.geoking.archimo.extract.output;

import fr.geoking.archimo.extract.model.BpmnFlow;
import fr.geoking.archimo.extract.model.ArchitectureInfo;
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

