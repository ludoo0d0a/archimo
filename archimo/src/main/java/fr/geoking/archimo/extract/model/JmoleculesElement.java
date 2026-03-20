package fr.geoking.archimo.extract.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A project class tagged by jMolecules types or annotations (DDD / events).
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record JmoleculesElement(String className, List<String> stereotypes) {}
