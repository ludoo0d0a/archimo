package fr.geoking.archimo.extract.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A compiled class that holds ArchUnit rules (@ArchTest fields or methods, etc.).
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ArchUnitRuleRef(
        String className,
        List<String> archTestMethodNames,
        List<String> staticArchRuleFieldNames,
        boolean analyzeClassesPresent
) {}
