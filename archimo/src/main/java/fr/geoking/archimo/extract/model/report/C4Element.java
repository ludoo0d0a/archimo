package fr.geoking.archimo.extract.model.report;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * One node in the report tree: actor, system, container, module, class, etc.
 * {@code attributes} hold parser-specific refs (e.g. {@code fqcn}, {@code moduleName}).
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public record C4Element(
        String id,
        C4ElementKind kind,
        String label,
        String technology,
        Map<String, String> attributes,
        List<C4OutboundLink> links,
        @JsonInclude(value = JsonInclude.Include.CUSTOM, valueFilter = C4Element.ExcludeAutoOrigin.class)
        C4ElementOrigin origin
) {
    /** Scan-built element (default origin). */
    public C4Element(String id, C4ElementKind kind, String label, String technology,
                     Map<String, String> attributes, List<C4OutboundLink> links) {
        this(id, kind, label, technology, attributes, links, C4ElementOrigin.AUTO);
    }

    /**
     * Jackson: exclude {@code origin} from JSON when {@link C4ElementOrigin#AUTO} (or null).
     */
    static final class ExcludeAutoOrigin {
        @Override
        public boolean equals(Object o) {
            return o == null || o == C4ElementOrigin.AUTO;
        }

        @Override
        public int hashCode() {
            return 1;
        }
    }
}
