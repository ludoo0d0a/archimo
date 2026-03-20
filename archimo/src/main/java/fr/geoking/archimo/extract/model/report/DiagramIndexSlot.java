package fr.geoking.archimo.extract.model.report;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Planned diagram file for the report UI: ordering and metadata come from the tree, not from a directory walk.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DiagramIndexSlot(
        String diagramId,
        /** {@code plantuml} or {@code mermaid} */
        String format,
        int c4Level,
        int c4Order,
        String navLabel,
        String levelKey,
        String category
) {}
