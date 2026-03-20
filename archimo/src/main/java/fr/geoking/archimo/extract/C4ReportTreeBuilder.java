package fr.geoking.archimo.extract;

import fr.geoking.archimo.extract.model.ArchitectureInfo;
import fr.geoking.archimo.extract.model.ClassDependency;
import fr.geoking.archimo.extract.model.EndpointFlow;
import fr.geoking.archimo.extract.model.EntityRelation;
import fr.geoking.archimo.extract.model.ExtractResult;
import fr.geoking.archimo.extract.model.ExternalHttpClient;
import fr.geoking.archimo.extract.model.FrameworkDesignInsights;
import fr.geoking.archimo.extract.model.MessagingFlow;
import fr.geoking.archimo.extract.model.ModuleDependency;
import fr.geoking.archimo.extract.model.report.C4Element;
import fr.geoking.archimo.extract.model.report.C4ElementKind;
import fr.geoking.archimo.extract.model.report.C4Group;
import fr.geoking.archimo.extract.model.report.C4LevelSection;
import fr.geoking.archimo.extract.model.report.C4OutboundLink;
import fr.geoking.archimo.extract.model.report.C4ReportTree;
import fr.geoking.archimo.extract.model.report.DiagramIndexSlot;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Builds the in-memory {@link C4ReportTree} from parsed extraction data. Diagram outputs and the site index
 * should use this structure as the source of truth for levels, grouping, and ordering.
 */
public final class C4ReportTreeBuilder {

    /** Kept in sync with {@link fr.geoking.archimo.extract.output.PlantUmlOutput} endpoint diagram cap. */
    private static final int MAX_ENDPOINT_SPECIFIC_DIAGRAMS = 80;

    private C4ReportTreeBuilder() {}

    public static C4ReportTree build(ApplicationModules modules, ExtractResult result) {
        if (result == null) {
            return C4ReportTree.empty();
        }
        String mainFqcn = result.applicationMainClass();
        String shortName = mainFqcn != null ? simpleName(mainFqcn) : "Application";

        List<C4LevelSection> sections = new ArrayList<>();
        sections.add(buildLevel1(result, shortName));
        sections.add(buildLevel2(result, shortName));
        sections.add(buildLevel3(modules, result));
        sections.add(buildLevel4(result));

        List<DiagramIndexSlot> slots = new ArrayList<>();
        fillDiagramSlots(modules, result, slots);

        return new C4ReportTree(shortName, mainFqcn, List.copyOf(sections), List.copyOf(slots));
    }

    private static C4LevelSection buildLevel1(ExtractResult result, String appTitle) {
        List<ArchitectureInfo> infos = nullToEmpty(result.architectureInfos());
        List<EndpointFlow> endpoints = nullToEmpty(result.endpointFlows());
        List<ExternalHttpClient> httpClients = nullToEmpty(result.externalHttpClients());
        List<MessagingFlow> messaging = nullToEmpty(result.messagingFlows());

        boolean hasInboundHttp = !endpoints.isEmpty() || containsLayer(infos, "controller");
        String userDesc = hasInboundHttp ? "Uses HTTP API / web UI" : "Primary actor / stakeholder";
        String appTech = result.applicationMainClass() != null
                ? "Spring Boot\\n" + result.applicationMainClass()
                : "Java application";

        List<C4Element> elements = new ArrayList<>();
        List<C4OutboundLink> userLinks = new ArrayList<>();
        userLinks.add(new C4OutboundLink("app", hasInboundHttp ? "Uses" : "Interacts with", hasInboundHttp ? "HTTPS" : "Various"));
        elements.add(new C4Element("user", C4ElementKind.PERSON, "User", userDesc, Map.of(), userLinks));

        List<C4OutboundLink> appOutbound = new ArrayList<>();
        elements.add(new C4Element("app", C4ElementKind.SOFTWARE_SYSTEM, appTitle, appTech, Map.of(), appOutbound));

        List<String> httpLabels = dedupeHttpLabels(httpClients);
        for (int i = 0; i < httpLabels.size(); i++) {
            String pid = "extHttp_" + i;
            appOutbound.add(new C4OutboundLink(pid, "HTTP client", null));
            elements.add(new C4Element(pid, C4ElementKind.EXTERNAL_SYSTEM, httpLabels.get(i), "Remote HTTP API", Map.of(), List.of()));
        }

        Set<String> seenMessaging = new LinkedHashSet<>();
        int msgIdx = 0;
        for (MessagingFlow f : messaging) {
            String dest = f.destination() != null ? f.destination() : "";
            String tech = f.technology() != null ? f.technology() : "";
            String key = tech + "|" + dest;
            if (!seenMessaging.add(key)) {
                continue;
            }
            String title = !dest.isBlank() ? dest : "Messaging";
            String techLabel = !tech.isBlank() ? tech : "Broker";
            String pid = "extMsg_" + msgIdx++;
            appOutbound.add(new C4OutboundLink(pid, "Messaging", null));
            elements.add(new C4Element(pid, C4ElementKind.MESSAGE_BROKER, title, techLabel, Map.of(), List.of()));
        }

        C4Group g = new C4Group("l1-context", "Actors, system, externals", 0, elements);
        return new C4LevelSection(1, "System context (L1)", List.of(g));
    }

    private static C4LevelSection buildLevel2(ExtractResult result, String appTitle) {
        List<ArchitectureInfo> infos = nullToEmpty(result.architectureInfos());
        List<EndpointFlow> endpoints = nullToEmpty(result.endpointFlows());
        List<EntityRelation> entityRelations = nullToEmpty(result.entityRelations());

        boolean hasInboundHttp = !endpoints.isEmpty() || containsLayer(infos, "controller");
        boolean hasDb = containsLayer(infos, "repository") || !entityRelations.isEmpty();
        String mainFqcn = result.applicationMainClass();
        String backendTech = mainFqcn != null ? "Spring Boot\\n" + mainFqcn : "Spring Boot / Java";
        String uiTech = hasInboundHttp ? "Browser; pages & REST clients" : "Browser, desktop, or embedded client";
        String dbTech = hasDb ? "Relational / JPA persistence" : "Persistence (none detected from scan)";

        List<C4Element> elements = List.of(
                new C4Element("user", C4ElementKind.PERSON, "User", "Uses the application", Map.of(), List.of(
                        new C4OutboundLink("web_ui", "Uses", "HTTPS"),
                        new C4OutboundLink("static_assets", "Loads assets", "HTTPS"))),
                new C4Element("static_assets", C4ElementKind.CONTAINER, "Static content", "HTML, CSS, JS, images", Map.of(), List.of()),
                new C4Element("web_ui", C4ElementKind.CONTAINER, "Web UI", uiTech, Map.of(), List.of(
                        new C4OutboundLink("backend", "Invokes", "HTTP / JSON"))),
                new C4Element("backend", C4ElementKind.CONTAINER, "Backend", backendTech, Map.of(), List.of(
                        new C4OutboundLink("static_assets", "May serve", "filesystem / CDN"),
                        new C4OutboundLink("database", "Reads & writes", "SQL / ORM"))),
                new C4Element("database", C4ElementKind.DATABASE, "Database", dbTech, Map.of(), List.of())
        );

        C4Group g = new C4Group("l2-containers", "Containers inside the system boundary", 0, elements);
        return new C4LevelSection(2, "Containers (L2)", List.of(g));
    }

    private static C4LevelSection buildLevel3(ApplicationModules modules, ExtractResult result) {
        List<C4Group> groups = new ArrayList<>();

        if (modules != null) {
            List<C4Element> moduleElements = new ArrayList<>();
            Map<String, String> displayToId = new LinkedHashMap<>();
            for (ApplicationModule m : sortedModules(modules)) {
                String display = m.getDisplayName();
                String id = "mod_" + sanitizeId(m.getIdentifier().toString());
                displayToId.put(display, id);
                Map<String, String> attr = new LinkedHashMap<>();
                attr.put("basePackage", m.getBasePackage().getName());
                attr.put("moduleIdentifier", m.getIdentifier().toString());
                moduleElements.add(new C4Element(id, C4ElementKind.MODULE, display,
                        m.getBasePackage().getName(), Map.copyOf(attr), new ArrayList<>()));
            }
            Map<String, List<C4OutboundLink>> linksByFrom = new LinkedHashMap<>();
            for (ModuleDependency dep : nullToEmpty(result.moduleDependencies())) {
                String fromId = displayToId.get(dep.fromModule());
                String toId = displayToId.get(dep.toModule());
                if (fromId == null || toId == null) {
                    continue;
                }
                linksByFrom.computeIfAbsent(fromId, k -> new ArrayList<>())
                        .add(new C4OutboundLink(toId, "depends on", null));
            }
            List<C4Element> withLinks = moduleElements.stream()
                    .map(e -> new C4Element(e.id(), e.kind(), e.label(), e.technology(), e.attributes(),
                            List.copyOf(linksByFrom.getOrDefault(e.id(), List.of()))))
                    .toList();
            groups.add(new C4Group("l3-modules", "Spring Modulith modules", 0, withLinks));
        }

        List<ArchitectureInfo> infos = nullToEmpty(result.architectureInfos());
        if (!infos.isEmpty()) {
            List<C4Element> components = infos.stream()
                    .map(info -> {
                        String id = classElementId(info.className());
                        Map<String, String> a = Map.of("fqcn", info.className(), "layer", info.layer());
                        return new C4Element(id, C4ElementKind.COMPONENT, simpleName(info.className()),
                                info.layer(), a, List.of());
                    })
                    .toList();
            groups.add(new C4Group("l3-backend-components", "Scanned types (backend composition)", 10, components));
        }

        if (groups.isEmpty()) {
            groups.add(new C4Group("l3-empty", "No module or type data", 0, List.of()));
        }
        return new C4LevelSection(3, "Backend composition (L3)", groups);
    }

    private static C4LevelSection buildLevel4(ExtractResult result) {
        List<ArchitectureInfo> infos = nullToEmpty(result.architectureInfos());
        Map<String, List<ArchitectureInfo>> byLayer = infos.stream().collect(Collectors.groupingBy(ArchitectureInfo::layer, LinkedHashMap::new, Collectors.toList()));

        List<C4Group> groups = new ArrayList<>();
        int order = 0;
        for (Map.Entry<String, List<ArchitectureInfo>> e : byLayer.entrySet()) {
            String layer = e.getKey();
            List<C4Element> classes = e.getValue().stream()
                    .map(info -> {
                        String id = classElementId(info.className());
                        Map<String, String> a = Map.of("fqcn", info.className(), "layer", layer);
                        return new C4Element(id, C4ElementKind.CLASS, simpleName(info.className()),
                                info.architectureType() != null ? info.architectureType() : layer, a, new ArrayList<>());
                    })
                    .toList();
            groups.add(new C4Group("l4-layer-" + sanitizeId(layer), capitalize(layer) + " layer", order, classes));
            order += 10;
        }

        List<ClassDependency> deps = nullToEmpty(result.classDependencies());
        if (!deps.isEmpty() && !groups.isEmpty()) {
            Map<String, C4Element> byId = new LinkedHashMap<>();
            for (C4Group g : groups) {
                for (C4Element el : g.elements()) {
                    byId.put(el.id(), el);
                }
            }
            for (ClassDependency d : deps) {
                String fromId = classElementId(d.fromClass());
                String toId = classElementId(d.toClass());
                C4Element fromEl = byId.get(fromId);
                if (fromEl == null) {
                    continue;
                }
                if (byId.get(toId) == null) {
                    continue;
                }
                List<C4OutboundLink> nl = new ArrayList<>(fromEl.links());
                nl.add(new C4OutboundLink(toId, "depends on", null));
                byId.put(fromId, new C4Element(fromEl.id(), fromEl.kind(), fromEl.label(), fromEl.technology(), fromEl.attributes(), nl));
            }
            List<C4Group> regrouped = groups.stream()
                    .map(g -> new C4Group(g.groupId(), g.title(), g.sortOrder(),
                            g.elements().stream().map(e -> byId.getOrDefault(e.id(), e)).toList()))
                    .toList();
            groups.clear();
            groups.addAll(regrouped);
        }

        if (groups.isEmpty()) {
            groups.add(new C4Group("l4-empty", "No class-level data", 0, List.of()));
        }
        return new C4LevelSection(4, "Code (L4)", groups);
    }

    private static void fillDiagramSlots(ApplicationModules modules, ExtractResult result, List<DiagramIndexSlot> slots) {
        List<ArchitectureInfo> infos = nullToEmpty(result.architectureInfos());
        List<EndpointFlow> endpoints = nullToEmpty(result.endpointFlows());

        slots.add(new DiagramIndexSlot("system-context", "plantuml", 1, 0, "L1 — System context", "system", "overview"));
        slots.add(new DiagramIndexSlot("c4-containers", "plantuml", 2, 0, "L2 — Containers", "container", "container"));

        if (modules != null) {
            slots.add(new DiagramIndexSlot("components", "plantuml", 3, 0, "L3 — Backend (all modules)", "component", "module"));
            for (ApplicationModule m : sortedModules(modules)) {
                String diagramId = modulePlantUmlId(m);
                String label = "L3 — Module: " + m.getIdentifier().toString().replace(".", " / ");
                slots.add(new DiagramIndexSlot(diagramId, "plantuml", 3, 20, label, "component", "module"));
            }
        }

        if (!infos.isEmpty()) {
            slots.add(new DiagramIndexSlot("architecture-layers", "plantuml", 3, 5, "L3 — Backend layers", "component", "layers"));
            slots.add(new DiagramIndexSlot("architecture-class-diagram", "plantuml", 4, 0, "L4 — Classes by layer", "code", "code"));
            slots.add(new DiagramIndexSlot("architecture-component-dependencies", "plantuml", 4, 5, "L4 — Component dependencies", "code", "code"));
            slots.add(new DiagramIndexSlot("architecture-flow", "plantuml", 0, 10, "Architecture flow", "dynamic", "flow"));
            slots.add(new DiagramIndexSlot("architecture-sequence", "plantuml", 0, 11, "Architecture sequence", "dynamic", "sequence"));
            slots.add(new DiagramIndexSlot("architecture-class-diagram", "mermaid", 4, 2, "L4 — Classes by layer (Mermaid)", "code", "code"));
            slots.add(new DiagramIndexSlot("architecture-component-dependencies", "mermaid", 4, 7, "L4 — Component dependencies (Mermaid)", "code", "code"));
            slots.add(new DiagramIndexSlot("architecture-flow", "mermaid", 0, 12, "Architecture flow (Mermaid)", "dynamic", "flow"));
            slots.add(new DiagramIndexSlot("architecture-sequence", "mermaid", 0, 13, "Architecture sequence (Mermaid)", "dynamic", "sequence"));
        }

        if (!endpoints.isEmpty()) {
            slots.add(new DiagramIndexSlot("endpoint-flow", "plantuml", 0, 30, "Endpoint flow", "endpoint", "flow"));
            slots.add(new DiagramIndexSlot("data-lineage-diagram", "plantuml", 0, 40, "Data lineage", "data", "data"));
            slots.add(new DiagramIndexSlot("endpoint-flow", "mermaid", 0, 31, "Endpoint flow (Mermaid)", "endpoint", "flow"));
            slots.add(new DiagramIndexSlot("data-lineage-diagram", "mermaid", 0, 41, "Data lineage (Mermaid)", "data", "data"));
            if (endpoints.size() <= MAX_ENDPOINT_SPECIFIC_DIAGRAMS) {
                for (EndpointFlow ep : endpoints) {
                    String slug = endpointFileSlug(ep);
                    slots.add(new DiagramIndexSlot("endpoint-sequence-" + slug, "plantuml", 0, 50, ep.httpMethod() + " " + ep.path(), "endpoint", "sequence"));
                    slots.add(new DiagramIndexSlot("endpoint-data-lineage-" + slug, "plantuml", 0, 51, ep.httpMethod() + " " + ep.path() + " (data)", "endpoint", "data"));
                }
            }
        }

        if (!nullToEmpty(result.entityRelations()).isEmpty()) {
            slots.add(new DiagramIndexSlot("entity-relationship", "plantuml", 0, 42, "Entity relationship", "data", "data"));
            slots.add(new DiagramIndexSlot("entity-relationship", "mermaid", 0, 43, "Entity relationship (Mermaid)", "data", "data"));
        }

        boolean deploy = !endpoints.isEmpty() || containsLayer(infos, "repository")
                || !nullToEmpty(result.messagingFlows()).isEmpty()
                || (result.infrastructureTopology() != null && !result.infrastructureTopology().isEmpty());
        if (deploy) {
            slots.add(new DiagramIndexSlot("deployment-diagram", "plantuml", 0, 60, "Deployment", "deployment", "deployment"));
            slots.add(new DiagramIndexSlot("deployment-diagram", "mermaid", 0, 61, "Deployment (Mermaid)", "deployment", "deployment"));
        }

        if (!nullToEmpty(result.messagingFlows()).isEmpty()) {
            slots.add(new DiagramIndexSlot("messaging-flows", "plantuml", 0, 70, "Messaging flows", "dynamic", "messaging"));
            slots.add(new DiagramIndexSlot("messaging-flows", "mermaid", 0, 71, "Messaging flows (Mermaid)", "dynamic", "messaging"));
        }
        if (!nullToEmpty(result.bpmnFlows()).isEmpty()) {
            slots.add(new DiagramIndexSlot("bpmn-flows", "plantuml", 0, 80, "BPMN flows", "dynamic", "bpmn"));
        }

        if (modules != null) {
            slots.add(new DiagramIndexSlot("module-dependencies", "mermaid", 3, 10, "L3 — Module dependencies (Mermaid)", "component", "module"));
            slots.add(new DiagramIndexSlot("event-flows", "mermaid", 0, 90, "Event flows", "dynamic", "flow"));
        }

        FrameworkDesignInsights insights = result.frameworkDesignInsights();
        if (insights != null && insights.emitFrameworkDiagrams()) {
            if (!insights.jmoleculesElements().isEmpty()) {
                slots.add(new DiagramIndexSlot("jmolecules-model", "mermaid", 4, 30, "L4 — jMolecules model", "code", "framework"));
            }
            boolean archUnitAny = insights.archUnitDeclaredInBuild() || insights.archUnitTypesReferencedInBytecode()
                    || !insights.archUnitRuleRefs().isEmpty();
            if (archUnitAny) {
                slots.add(new DiagramIndexSlot("archunit-rules-overview", "mermaid", 0, 100, "ArchUnit rules overview", "supporting", "framework"));
            }
            if (!insights.findings().isEmpty()) {
                slots.add(new DiagramIndexSlot("design-findings", "mermaid", 0, 101, "Design findings", "supporting", "framework"));
            }
        }
    }

    private static String modulePlantUmlId(ApplicationModule module) {
        return "module-" + module.getIdentifier().toString();
    }

    private static List<ApplicationModule> sortedModules(ApplicationModules modules) {
        return StreamSupport.stream(modules.spliterator(), false)
                .sorted()
                .toList();
    }

    /** Same stem as {@code endpoint-sequence-<stem>.puml} in PlantUML output. */
    private static String endpointFileSlug(EndpointFlow ep) {
        return sanitizeId(ep.httpMethod() + "_" + ep.path() + "_" + ep.controllerMethod());
    }

    private static List<String> dedupeHttpLabels(List<ExternalHttpClient> httpClients) {
        List<String> httpLabels = new ArrayList<>();
        Set<String> seenHttp = new LinkedHashSet<>();
        for (ExternalHttpClient c : httpClients) {
            String label = externalHttpSystemLabel(c);
            if (seenHttp.add(label.toLowerCase())) {
                httpLabels.add(label);
            }
        }
        return httpLabels;
    }

    private static String externalHttpSystemLabel(ExternalHttpClient c) {
        String d = c.detail();
        if (d != null && !d.isBlank()) {
            if (d.startsWith("url=")) {
                return truncateLabel(d.substring(4).trim(), 96);
            }
            if (d.startsWith("name=")) {
                String rest = d.substring(5).trim();
                int comma = rest.indexOf(',');
                String name = comma > 0 ? rest.substring(0, comma).trim() : rest;
                return !name.isEmpty() ? name : truncateLabel(d, 96);
            }
            if (d.startsWith("serviceId=")) {
                return truncateLabel(d.substring(10).trim(), 96);
            }
            return truncateLabel(d, 96);
        }
        return c.clientKind() + ": " + simpleName(c.declaringClass());
    }

    private static String truncateLabel(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max - 3) + "...";
    }

    private static String classElementId(String fqcn) {
        return fqcn.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private static String sanitizeId(String s) {
        return s.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private static String simpleName(String fqcn) {
        int idx = fqcn.lastIndexOf('.');
        return idx >= 0 ? fqcn.substring(idx + 1) : fqcn;
    }

    private static String capitalize(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static boolean containsLayer(List<ArchitectureInfo> infos, String layer) {
        return infos.stream().anyMatch(i -> layer.equals(i.layer()));
    }

    private static <T> List<T> nullToEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}
