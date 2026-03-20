package fr.geoking.archimo.extract.model.report;

/**
 * Kind of node in the in-memory C4 / architecture report tree (not necessarily a C4-PlantUML macro).
 */
public enum C4ElementKind {
    PERSON,
    SOFTWARE_SYSTEM,
    EXTERNAL_SYSTEM,
    CONTAINER,
    DATABASE,
    MODULE,
    COMPONENT,
    CLASS,
    MESSAGE_BROKER,
    SUPPORTING
}
