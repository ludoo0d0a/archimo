package fr.geoking.archimo.report;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Enables architecture report generation when tests run.
 * Add this to a test class to generate PlantUML, Mermaid and website report
 * under {@code target/archimo-docs} (or {@code archimo.report.outputDir}).
 *
 * <p>Example:
 * <pre>
 * &#64;ExtendWith(ArchimoReportExtension.class)
 * &#64;ArchimoReport(mainClass = MyApplication.class)
 * class MyApplicationTests { ... }
 * </pre>
 *
 * <p>Or set system property {@code archimo.appClass=com.example.MyApplication}
 * and use only {@code @ExtendWith(ArchimoReportExtension.class)}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(ArchimoReportExtension.class)
public @interface ArchimoReport {

    /**
     * Main Spring Boot / Modulith application class to analyze.
     * If not set, the extension uses system property {@code archimo.appClass}.
     */
    Class<?> value() default void.class;

    /** Alias for {@link #value()}. */
    Class<?> mainClass() default void.class;
}
