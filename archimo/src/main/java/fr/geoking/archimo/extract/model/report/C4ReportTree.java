package fr.geoking.archimo.extract.model.report;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Optional;

/**
 * In-memory architecture report: C4 levels, elements, links, and ordered diagram slots for the static site.
 * Built from {@link fr.geoking.archimo.extract.model.ExtractResult} during extraction; diagram writers and
 * {@code site-index.json} consume this structure before / alongside generated files.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public record C4ReportTree(
        String applicationShortName,
        String applicationMainClassFqcn,
        List<C4LevelSection> levelSections,
        List<DiagramIndexSlot> diagramSlots
) {

    private static final C4ReportTree EMPTY = new C4ReportTree("Application", null, List.of(), List.of());

    public static C4ReportTree empty() {
        return EMPTY;
    }

    public Optional<C4LevelSection> section(int level) {
        return levelSections.stream().filter(s -> s.level() == level).findFirst();
    }
}
