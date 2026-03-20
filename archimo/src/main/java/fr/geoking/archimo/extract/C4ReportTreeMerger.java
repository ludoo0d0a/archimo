package fr.geoking.archimo.extract;

import fr.geoking.archimo.extract.model.report.C4Element;
import fr.geoking.archimo.extract.model.report.C4ElementOrigin;
import fr.geoking.archimo.extract.model.report.C4Group;
import fr.geoking.archimo.extract.model.report.C4LevelSection;
import fr.geoking.archimo.extract.model.report.C4OutboundLink;
import fr.geoking.archimo.extract.model.report.C4ReportTree;
import fr.geoking.archimo.extract.model.report.DiagramIndexSlot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Merges a partial {@code archimo.mf} manifest tree with a fully built scan tree: manifest seeds structure
 * (e.g. external systems, people); the scan completes and overrides element fields when both define the same id.
 */
public final class C4ReportTreeMerger {

    private C4ReportTreeMerger() {}

    public static C4ReportTree merge(C4ReportTree manifest, C4ReportTree scanned, Consumer<String> warn) {
        if (manifest == null || isWhollyEmptyManifest(manifest)) {
            return scanned;
        }
        if (scanned == null) {
            return ArchimoManifestLoader.normalizeTree(manifest);
        }

        List<MutSection> merged = toMutableSections(manifest.levelSections());
        Map<String, ElementRef> index = buildIndex(merged);

        for (C4LevelSection sec : scanned.levelSections()) {
            MutSection targetSec = findOrAddSection(merged, sec);
            for (C4Group g : sec.groups()) {
                MutGroup targetGroup = findOrAddGroup(targetSec, g);
                for (C4Element el : g.elements()) {
                    ElementRef ref = index.get(el.id());
                    if (ref == null) {
                        targetGroup.elements.add(copyElement(el));
                        index.put(el.id(), new ElementRef(targetSec, targetGroup, targetGroup.elements.size() - 1));
                    } else {
                        C4Element existing = ref.group.elements.get(ref.elementIndex);
                        mergeElementFields(existing, el, warn);
                        ref.group.elements.set(ref.elementIndex,
                                unionLinks(existing, el, warn));
                    }
                }
            }
        }

        List<DiagramIndexSlot> slots = mergeSlots(manifest.diagramSlots(), scanned.diagramSlots(), warn);

        C4ReportTree out = new C4ReportTree(
                scanned.applicationShortName(),
                scanned.applicationMainClassFqcn(),
                freezeSections(merged),
                slots
        );

        if (manifest.applicationShortName() != null
                && !manifest.applicationShortName().isBlank()
                && !Objects.equals(manifest.applicationShortName(), scanned.applicationShortName())) {
            warn.accept("archimo.mf: applicationShortName '" + manifest.applicationShortName()
                    + "' differs from scanned '" + scanned.applicationShortName() + "'; using scanned name.");
        }
        if (manifest.applicationMainClassFqcn() != null
                && !manifest.applicationMainClassFqcn().isBlank()
                && scanned.applicationMainClassFqcn() != null
                && !Objects.equals(manifest.applicationMainClassFqcn(), scanned.applicationMainClassFqcn())) {
            warn.accept("archimo.mf: applicationMainClassFqcn '" + manifest.applicationMainClassFqcn()
                    + "' differs from scanned '" + scanned.applicationMainClassFqcn() + "'; using scanned class.");
        }

        return out;
    }

    private static boolean isWhollyEmptyManifest(C4ReportTree manifest) {
        boolean noSections = manifest.levelSections() == null || manifest.levelSections().isEmpty();
        boolean noSlots = manifest.diagramSlots() == null || manifest.diagramSlots().isEmpty();
        return noSections && noSlots;
    }

    private static List<MutSection> toMutableSections(List<C4LevelSection> sections) {
        List<MutSection> out = new ArrayList<>();
        if (sections == null) {
            return out;
        }
        for (C4LevelSection sec : sections) {
            MutSection m = new MutSection();
            m.level = sec.level();
            m.title = sec.title() != null ? sec.title() : ("Level " + sec.level());
            for (C4Group g : nullSafe(sec.groups())) {
                MutGroup mg = new MutGroup();
                mg.groupId = g.groupId();
                mg.title = g.title();
                mg.sortOrder = g.sortOrder();
                for (C4Element e : nullSafe(g.elements())) {
                    mg.elements.add(copyElement(e));
                }
                m.groups.add(mg);
            }
            out.add(m);
        }
        return out;
    }

    private static MutSection findOrAddSection(List<MutSection> merged, C4LevelSection sec) {
        for (MutSection m : merged) {
            if (m.level == sec.level()) {
                return m;
            }
        }
        MutSection m = new MutSection();
        m.level = sec.level();
        m.title = sec.title() != null ? sec.title() : ("Level " + sec.level());
        merged.add(m);
        return m;
    }

    private static MutGroup findOrAddGroup(MutSection sec, C4Group g) {
        for (MutGroup mg : sec.groups) {
            if (Objects.equals(mg.groupId, g.groupId())) {
                return mg;
            }
        }
        MutGroup mg = new MutGroup();
        mg.groupId = g.groupId();
        mg.title = g.title();
        mg.sortOrder = g.sortOrder();
        sec.groups.add(mg);
        return mg;
    }

    private static Map<String, ElementRef> buildIndex(List<MutSection> merged) {
        Map<String, ElementRef> index = new LinkedHashMap<>();
        for (MutSection sec : merged) {
            for (MutGroup g : sec.groups) {
                for (int i = 0; i < g.elements.size(); i++) {
                    index.put(g.elements.get(i).id(), new ElementRef(sec, g, i));
                }
            }
        }
        return index;
    }

    private static void mergeElementFields(C4Element existing, C4Element scanned, Consumer<String> warn) {
        if (!Objects.equals(existing.label(), scanned.label())
                || !Objects.equals(existing.kind(), scanned.kind())
                || !Objects.equals(existing.technology(), scanned.technology())
                || !Objects.equals(existing.attributes(), scanned.attributes())) {
            warn.accept("C4 element '" + existing.id() + "': archimo.mf differs from scan "
                    + "(label/kind/technology/attributes); using scanned values.");
        }
    }

    private static C4Element unionLinks(C4Element manifestEl, C4Element scannedEl, Consumer<String> warn) {
        Map<LinkKey, C4OutboundLink> byKey = new LinkedHashMap<>();
        for (C4OutboundLink l : nullSafe(manifestEl.links())) {
            byKey.put(new LinkKey(l.targetElementId(), l.label()), l);
        }
        for (C4OutboundLink l : nullSafe(scannedEl.links())) {
            LinkKey k = new LinkKey(l.targetElementId(), l.label());
            C4OutboundLink prev = byKey.get(k);
            if (prev == null) {
                byKey.put(k, l);
            } else if (!Objects.equals(prev.technology(), l.technology())) {
                warn.accept("C4 link from '" + manifestEl.id() + "' to '" + l.targetElementId()
                        + "' (" + l.label() + "): technology differs; using scanned link.");
                byKey.put(k, l);
            }
        }
        return new C4Element(
                scannedEl.id(),
                scannedEl.kind(),
                scannedEl.label(),
                scannedEl.technology(),
                scannedEl.attributes() != null ? Map.copyOf(scannedEl.attributes()) : Map.of(),
                List.copyOf(byKey.values())
        );
    }

    private static List<DiagramIndexSlot> mergeSlots(
            List<DiagramIndexSlot> manifestSlots,
            List<DiagramIndexSlot> scannedSlots,
            Consumer<String> warn) {
        List<DiagramIndexSlot> out = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        if (manifestSlots != null) {
            for (DiagramIndexSlot s : manifestSlots) {
                String k = slotKey(s);
                if (keys.add(k)) {
                    out.add(s);
                }
            }
        }
        if (scannedSlots != null) {
            for (DiagramIndexSlot s : scannedSlots) {
                String k = slotKey(s);
                if (keys.add(k)) {
                    out.add(s);
                } else {
                    warn.accept("Diagram slot '" + k + "' is defined in both archimo.mf and scan; keeping manifest entry.");
                }
            }
        }
        return List.copyOf(out);
    }

    private static String slotKey(DiagramIndexSlot s) {
        return s.diagramId() + "|" + s.format();
    }

    private static List<C4LevelSection> freezeSections(List<MutSection> merged) {
        List<C4LevelSection> sections = new ArrayList<>();
        merged.sort((a, b) -> Integer.compare(a.level, b.level));
        for (MutSection m : merged) {
            List<C4Group> groups = new ArrayList<>();
            m.groups.sort((a, b) -> Integer.compare(a.sortOrder, b.sortOrder));
            for (MutGroup g : m.groups) {
                List<C4Element> els = new ArrayList<>();
                for (C4Element e : g.elements) {
                    els.add(freezeElement(e));
                }
                groups.add(new C4Group(g.groupId, g.title, g.sortOrder, List.copyOf(els)));
            }
            sections.add(new C4LevelSection(m.level, m.title, List.copyOf(groups)));
        }
        return List.copyOf(sections);
    }

    private static C4Element freezeElement(C4Element e) {
        return new C4Element(
                e.id(),
                e.kind(),
                e.label(),
                e.technology(),
                e.attributes() != null ? Map.copyOf(e.attributes()) : Map.of(),
                List.copyOf(nullSafe(e.links())),
                e.origin()
        );
    }

    private static C4Element copyElement(C4Element e) {
        List<C4OutboundLink> links = new ArrayList<>(nullSafe(e.links()));
        Map<String, String> attrs = e.attributes() != null
                ? new LinkedHashMap<>(e.attributes())
                : new LinkedHashMap<>();
        return new C4Element(e.id(), e.kind(), e.label(), e.technology(), attrs, links, e.origin());
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static final class MutSection {
        int level;
        String title;
        final List<MutGroup> groups = new ArrayList<>();
    }

    private static final class MutGroup {
        String groupId;
        String title;
        int sortOrder;
        final List<C4Element> elements = new ArrayList<>();
    }

    private record ElementRef(MutSection section, MutGroup group, int elementIndex) {}

    private record LinkKey(String targetId, String label) {}
}
