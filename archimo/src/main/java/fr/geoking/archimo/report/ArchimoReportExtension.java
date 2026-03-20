package fr.geoking.archimo.report;

import fr.geoking.archimo.extract.MessagingScanConcurrency;
import fr.geoking.archimo.extract.ModulithExtractor;
import fr.geoking.archimo.extract.output.OutputFormat;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.modulith.core.ApplicationModules;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.Set;

/**
 * JUnit 5 extension that runs the Modulith extractor after all tests in the class,
 * writing PlantUML, Mermaid, JSON and website report to {@code target/archimo-docs}
 * (or {@code archimo.report.outputDir}). Use with {@link ArchimoReport} or set
 * {@code archimo.appClass} and optionally {@code archimo.report.outputDir},
 * {@code archimo.messagingScanConcurrency} (auto, virtual, platform).
 */
public final class ArchimoReportExtension implements AfterAllCallback {

    private static final String PROP_OUTPUT_DIR = "archimo.report.outputDir";
    private static final String PROP_APP_CLASS = "archimo.appClass";
    private static final String PROP_MESSAGING_SCAN_CONCURRENCY = "archimo.messagingScanConcurrency";
    private static final String DEFAULT_OUTPUT = "target/archimo-docs";

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        Class<?> mainClass = resolveMainClass(context);
        if (mainClass == null) {
            return;
        }
        Path outputDir = resolveOutputDir();
        ApplicationModules modules = ApplicationModules.of(mainClass);
        MessagingScanConcurrency messagingScan = MessagingScanConcurrency.parseCli(System.getProperty(PROP_MESSAGING_SCAN_CONCURRENCY));
        Set<OutputFormat> formats = EnumSet.copyOf(OutputFormat.DEFAULT_DIAGRAM_FORMATS);
        formats.add(OutputFormat.JSON);
        ModulithExtractor extractor = new ModulithExtractor(modules, outputDir, null, false, messagingScan, mainClass.getName(), formats);
        extractor.extract();
    }

    private static Class<?> resolveMainClass(ExtensionContext context) {
        Class<?> testClass = context.getRequiredTestClass();
        ArchimoReport ann = testClass.getAnnotation(ArchimoReport.class);
        if (ann != null) {
            Class<?> c = ann.mainClass() != void.class ? ann.mainClass() : ann.value();
            if (c != void.class) return c;
        }
        String fqcn = System.getProperty(PROP_APP_CLASS);
        if (fqcn != null && !fqcn.isBlank()) {
            try {
                return Class.forName(fqcn.trim());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("archimo.appClass not found: " + fqcn, e);
            }
        }
        return null;
    }

    private static Path resolveOutputDir() {
        String dir = System.getProperty(PROP_OUTPUT_DIR, DEFAULT_OUTPUT);
        Path path = Paths.get(dir);
        if (!path.isAbsolute()) {
            path = Paths.get(System.getProperty("user.dir", ".")).resolve(path);
        }
        return path;
    }
}
