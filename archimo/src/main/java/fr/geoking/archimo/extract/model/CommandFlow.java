package fr.geoking.archimo.extract.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Represents a command flow: a command type handled by a target module.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record CommandFlow(
    String commandType,
    String targetModule
) {}
