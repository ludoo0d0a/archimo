package fr.geoking.archimo.extract.model.report;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Directed link between {@link C4Element} nodes (logical target id + label).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record C4OutboundLink(
        String targetElementId,
        String label,
        String technology
) {}
