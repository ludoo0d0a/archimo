package fr.geoking.archimo.extract.output;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Factory for diagram outputs based on requested formats.
 */
public final class DiagramOutputFactory {

    private DiagramOutputFactory() {}

    public static List<DiagramOutput> defaultOutputs() {
        return create(EnumSet.allOf(OutputFormat.class));
    }

    public static List<DiagramOutput> create(Set<OutputFormat> formats) {
        return formats.stream()
                .distinct()
                .map(format -> switch (format) {
                    case PLANTUML -> new PlantUmlOutput();
                    case MERMAID -> new MermaidOutput();
                })
                .toList();
    }
}

