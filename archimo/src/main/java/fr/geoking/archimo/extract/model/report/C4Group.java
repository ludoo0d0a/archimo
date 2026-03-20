package fr.geoking.archimo.extract.model.report;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Named bucket inside a C4 level (e.g. "containers", "spring-modules", "layer-controller").
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public record C4Group(
        String groupId,
        String title,
        int sortOrder,
        List<C4Element> elements
) {}
