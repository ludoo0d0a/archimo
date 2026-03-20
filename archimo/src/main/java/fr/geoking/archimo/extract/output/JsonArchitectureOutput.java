package fr.geoking.archimo.extract.output;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import fr.geoking.archimo.extract.model.ExtractResult;
import fr.geoking.archimo.extract.model.report.C4ReportTree;
import org.springframework.modulith.core.ApplicationModules;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes the merged {@link C4ReportTree} to {@code architecture.json} at the report root (same JSON model as {@code archimo.mf}).
 */
public final class JsonArchitectureOutput implements DiagramOutput {

    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @Override
    public void write(ApplicationModules modules, Path outputDir, ExtractResult result, C4ReportTree reportTree)
            throws IOException {
        Files.createDirectories(outputDir);
        mapper.writeValue(outputDir.resolve("architecture.json").toFile(), reportTree);
    }
}
