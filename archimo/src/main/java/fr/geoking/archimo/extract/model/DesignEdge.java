package fr.geoking.archimo.extract.model;

/**
 * A directed relation between two jMolecules-tagged classes (dependencies / references).
 */
public record DesignEdge(String fromClass, String toClass, String kind) {}
