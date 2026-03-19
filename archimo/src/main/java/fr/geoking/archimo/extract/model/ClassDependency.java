package fr.geoking.archimo.extract.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Direct class-to-class dependency discovered from bytecode analysis.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ClassDependency(
        String fromClass,
        String toClass
) {}

