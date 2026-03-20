package fr.geoking.archimo.extract.output;

import java.util.List;
import java.util.Set;

/**
 * Factory for diagram outputs based on requested formats.
 */
public final class DiagramOutputFactory {

    private DiagramOutputFactory() {}

    public static List<DiagramOutput> defaultOutputs() {
        return create(OutputFormat.DEFAULT_DIAGRAM_FORMATS);
    }

    public static List<DiagramOutput> create(Set<OutputFormat> formats) {
        if (formats == null || formats.isEmpty()) {
            return create(OutputFormat.DEFAULT_DIAGRAM_FORMATS);
        }
        return formats.stream()
                .distinct()
                .map(format -> switch (format) {
                    case PLANTUML -> new PlantUmlOutput();
                    case MERMAID -> new MermaidOutput();
                    case JSON -> new JsonArchitectureOutput();
                })
                .toList();
    }
}

