package fr.geoking.archimo.extract.model;

/**
 * Heuristic constraint / smell / note (Archimo does not execute ArchUnit rules).
 */
public record DesignFinding(String severity, String code, String message, String relatedClass) {

    public static final String SEVERITY_INFO = "INFO";
    public static final String SEVERITY_WARN = "WARN";
}
