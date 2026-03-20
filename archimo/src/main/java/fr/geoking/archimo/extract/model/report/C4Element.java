package fr.geoking.archimo.extract.model.report;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * One node in the report tree: actor, system, container, module, class, etc.
 * {@code attributes} hold parser-specific refs (e.g. {@code fqcn}, {@code moduleName}).
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record C4Element(
        String id,
        C4ElementKind kind,
        String label,
        String technology,
        Map<String, String> attributes,
        List<C4OutboundLink> links
) {}
