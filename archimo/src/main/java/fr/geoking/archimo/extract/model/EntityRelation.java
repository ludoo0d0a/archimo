package fr.geoking.archimo.extract.model;

/**
 * Represents an inferred JPA entity relationship between two classes.
 */
public record EntityRelation(
        String fromEntity,
        String toEntity,
        String relationType
) {}
