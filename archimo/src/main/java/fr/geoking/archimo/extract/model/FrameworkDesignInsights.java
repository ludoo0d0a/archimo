package fr.geoking.archimo.extract.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Build + bytecode signals for jMolecules and ArchUnit, relations, and static design notes.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record FrameworkDesignInsights(
        boolean archUnitDeclaredInBuild,
        boolean jmoleculesDeclaredInBuild,
        boolean archUnitTypesReferencedInBytecode,
        List<JmoleculesElement> jmoleculesElements,
        List<DesignEdge> jmoleculesEdges,
        List<ArchUnitRuleRef> archUnitRuleRefs,
        List<DesignFinding> findings
) {
    public static FrameworkDesignInsights empty() {
        return new FrameworkDesignInsights(false, false, false, List.of(), List.of(), List.of(), List.of());
    }

    /** Whether to emit dedicated Mermaid diagrams for frameworks / findings. */
    public boolean emitFrameworkDiagrams() {
        return archUnitDeclaredInBuild
                || jmoleculesDeclaredInBuild
                || archUnitTypesReferencedInBytecode
                || !jmoleculesElements.isEmpty()
                || !archUnitRuleRefs.isEmpty()
                || !findings.isEmpty();
    }
}
