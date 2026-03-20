package fr.geoking.archimo.extract.model.report;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * One C4 static level (1–4) or supporting bucket (0) with grouped elements.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record C4LevelSection(
        int level,
        String title,
        List<C4Group> groups
) {}
