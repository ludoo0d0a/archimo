package fr.geoking.archimo.extract.output;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

public enum OutputFormat {
    PLANTUML,
    MERMAID,
    /** Merged C4 report tree as JSON (same shape as {@code archimo.mf}). */
    JSON;

    public static final Set<OutputFormat> DEFAULT_DIAGRAM_FORMATS = EnumSet.of(PLANTUML, MERMAID);

    /**
     * Parses comma-separated tokens ({@code plantuml}, {@code mermaid}, {@code json}) for {@code -o} / {@code --output-format}.
     */
    public static Set<OutputFormat> parseCsv(String csv, Consumer<String> warnUnknown) {
        Set<OutputFormat> out = new LinkedHashSet<>();
        if (csv == null || csv.isBlank()) {
            return out;
        }
        for (String part : csv.split(",")) {
            String t = part.trim().toLowerCase(Locale.ROOT);
            if (t.isEmpty()) {
                continue;
            }
            try {
                out.add(OutputFormat.valueOf(t.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                if (warnUnknown != null) {
                    warnUnknown.accept("Unknown output format '" + part.trim() + "' (expected plantuml, mermaid, json).");
                }
            }
        }
        return out;
    }
}

