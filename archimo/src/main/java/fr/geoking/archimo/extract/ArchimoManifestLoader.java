package fr.geoking.archimo.extract;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import fr.geoking.archimo.extract.model.report.C4Element;
import fr.geoking.archimo.extract.model.report.C4Group;
import fr.geoking.archimo.extract.model.report.C4LevelSection;
import fr.geoking.archimo.extract.model.report.C4ReportTree;
import fr.geoking.archimo.extract.model.report.DiagramIndexSlot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Loads {@code archimo.mf} from the project root. The file uses the same JSON shape as the exported
 * architecture tree ({@link C4ReportTree}), but any field may be omitted.
 */
public final class ArchimoManifestLoader {

    public static final String MANIFEST_FILE_NAME = "archimo.mf";

    private ArchimoManifestLoader() {}

    public static ObjectMapper manifestMapper() {
        return JsonMapper.builder()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .build();
    }

    /**
     * Reads and normalizes {@code projectRoot/archimo.mf} if the file exists.
     */
    public static Optional<C4ReportTree> loadIfPresent(Path projectRoot) throws IOException {
        if (projectRoot == null || !Files.isDirectory(projectRoot)) {
            return Optional.empty();
        }
        Path mf = projectRoot.resolve(MANIFEST_FILE_NAME);
        if (!Files.isRegularFile(mf)) {
            return Optional.empty();
        }
        C4ReportTree raw = manifestMapper().readValue(mf.toFile(), C4ReportTree.class);
        return Optional.of(normalizeTree(raw));
    }

    public static C4ReportTree normalizeTree(C4ReportTree t) {
        if (t == null) {
            return C4ReportTree.empty();
        }
        String shortName = blankToNull(t.applicationShortName());
        String fqcn = blankToNull(t.applicationMainClassFqcn());
        List<C4LevelSection> sections = normalizeSections(t.levelSections());
        List<DiagramIndexSlot> slots = t.diagramSlots() != null ? List.copyOf(t.diagramSlots()) : List.of();
        return new C4ReportTree(
                shortName != null ? shortName : "Application",
                fqcn,
                sections,
                slots
        );
    }

    private static List<C4LevelSection> normalizeSections(List<C4LevelSection> sections) {
        if (sections == null || sections.isEmpty()) {
            return List.of();
        }
        List<C4LevelSection> out = new ArrayList<>();
        for (C4LevelSection sec : sections) {
            if (sec == null) {
                continue;
            }
            List<C4Group> groups = normalizeGroups(sec.groups());
            String title = sec.title() != null && !sec.title().isBlank() ? sec.title() : ("Level " + sec.level());
            out.add(new C4LevelSection(sec.level(), title, groups));
        }
        return List.copyOf(out);
    }

    private static List<C4Group> normalizeGroups(List<C4Group> groups) {
        if (groups == null || groups.isEmpty()) {
            return List.of();
        }
        List<C4Group> out = new ArrayList<>();
        for (C4Group g : groups) {
            if (g == null) {
                continue;
            }
            List<C4Element> els = g.elements() != null ? g.elements().stream().filter(e -> e != null).map(ArchimoManifestLoader::normalizeElement).toList() : List.of();
            String gid = g.groupId() != null ? g.groupId() : "group";
            String gtitle = g.title() != null ? g.title() : gid;
            out.add(new C4Group(gid, gtitle, g.sortOrder(), els));
        }
        return List.copyOf(out);
    }

    private static C4Element normalizeElement(C4Element e) {
        return new C4Element(
                e.id(),
                e.kind(),
                e.label() != null ? e.label() : e.id(),
                e.technology() != null ? e.technology() : "",
                e.attributes() != null ? e.attributes() : java.util.Map.of(),
                e.links() != null ? e.links() : List.of()
        );
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s;
    }
}
