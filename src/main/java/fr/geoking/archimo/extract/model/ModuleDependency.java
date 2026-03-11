package fr.geoking.archimo.extract.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Direct dependency between two modules. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ModuleDependency(
    String fromModule,
    String toModule
) {}

