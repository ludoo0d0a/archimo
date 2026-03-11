package fr.geoking.archimo.extract.output;

import fr.geoking.archimo.extract.model.ExtractResult;
import org.springframework.modulith.core.ApplicationModules;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Strategy for producing diagram-oriented output (PlantUML, Mermaid, …).
 */
public interface DiagramOutput {

    void write(ApplicationModules modules, Path outputDir, ExtractResult result) throws IOException;
}

