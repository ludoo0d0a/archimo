package fr.geoking.archimo.extract.output;

import fr.geoking.archimo.extract.model.EventFlow;
import fr.geoking.archimo.extract.model.ExtractResult;
import fr.geoking.archimo.extract.model.ModuleDependency;
import org.springframework.modulith.core.ApplicationModules;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Writes Mermaid diagrams for event flows, sequences and module dependencies.
 */
public final class MermaidOutput implements DiagramOutput {

    @Override
    public void write(ApplicationModules modules, Path outputDir, ExtractResult result) throws IOException {
        List<EventFlow> flows = result.flows();
        List<ModuleDependency> deps = result.moduleDependencies();
        writeMermaidEventFlows(outputDir, flows);
        writeMermaidSequences(outputDir, flows);
        writeMermaidModuleDependencies(outputDir, deps);
    }

    private void writeMermaidEventFlows(Path outputDir, List<EventFlow> flows) throws IOException {
        Path mermaidDir = outputDir.resolve("mermaid");
        Files.createDirectories(mermaidDir);
        StringBuilder m = new StringBuilder();
        m.append("%% Event flows: publisher module -> event -> listener modules\n");
        m.append("flowchart LR\n");
        for (EventFlow f : flows) {
            String pub = sanitizeId(f.publisherModule());
            String ev = sanitizeId(f.eventType());
            for (String listener : f.listenerModules()) {
                String lis = sanitizeId(listener);
                m.append("  ").append(pub).append(" -->|").append(ev).append("| ").append(lis).append("\n");
            }
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
        for (ModuleDependency d : deps) {
            m.append("  ").append(sanitizeId(d.fromModule())).append(" --> ").append(sanitizeId(d.toModule())).append("\n");
        }
        Files.writeString(dir.resolve("module-dependencies.mmd"), m.toString());
    }

    private static String sanitizeId(String s) {
        if (s == null) return "null";
        return s.replaceAll("[^a-zA-Z0-9_]", "_");
    }
}

