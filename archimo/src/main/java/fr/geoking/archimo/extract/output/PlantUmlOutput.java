package fr.geoking.archimo.extract.output;

import fr.geoking.archimo.extract.model.ExtractResult;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;
import org.springframework.modulith.docs.Documenter.DiagramOptions;
import org.springframework.modulith.docs.Documenter.Options;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Writes PlantUML C4 diagrams and module canvases using Spring Modulith's Documenter.
 */
public final class PlantUmlOutput implements DiagramOutput {

    @Override
    public void write(ApplicationModules modules, Path outputDir, ExtractResult result) throws IOException {
        Options docOptions = Options.defaults().withOutputFolder(outputDir.toAbsolutePath().toString());
        Documenter documenter = new Documenter(modules, docOptions);
        documenter
                .writeModulesAsPlantUml(DiagramOptions.defaults().withStyle(DiagramOptions.DiagramStyle.C4))
                .writeIndividualModulesAsPlantUml(DiagramOptions.defaults().withStyle(DiagramOptions.DiagramStyle.C4))
                .writeModuleCanvases();
    }
}

