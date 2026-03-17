package fr.geoking.archimo.extract.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Represents architectural layer information for a component. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ArchitectureInfo(
    String className,
    String layer, // controller, service, repository, domain, etc.
    String architectureType // mvc, hexagonal, etc.
) {}
