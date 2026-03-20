package fr.geoking.archimo.extract.output;

import fr.geoking.archimo.extract.model.ExtractResult;
import fr.geoking.archimo.extract.model.report.C4ReportTree;
import org.springframework.modulith.core.ApplicationModules;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Strategy for producing diagram-oriented output (PlantUML, Mermaid, …).
 * {@link C4ReportTree} is built during extraction and drives C4 levels, grouping, and site ordering.
 */
public interface DiagramOutput {

    void write(ApplicationModules modules, Path outputDir, ExtractResult result, C4ReportTree reportTree) throws IOException;
}

