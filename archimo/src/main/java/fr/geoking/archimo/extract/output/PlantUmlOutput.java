package fr.geoking.archimo.extract.output;

import fr.geoking.archimo.extract.model.ExtractResult;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;
import org.springframework.modulith.docs.Documenter.DiagramOptions;
import org.springframework.modulith.docs.Documenter.Options;

import fr.geoking.archimo.extract.model.ArchitectureInfo;
import fr.geoking.archimo.extract.model.BpmnFlow;
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

