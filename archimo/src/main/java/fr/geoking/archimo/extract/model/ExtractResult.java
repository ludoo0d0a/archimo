package fr.geoking.archimo.extract.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import fr.geoking.archimo.model.ModuleEvents;

import java.util.List;

/**
 * Container for all extracted artifacts (events map, flows, sequences, commands).
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ExtractResult(
    List<ModuleEvents> eventsMap,
    List<EventFlow> flows,
    List<SequenceFlow> sequences,
    List<ModuleDependency> moduleDependencies,
    List<ClassDependency> classDependencies,
    List<EntityRelation> entityRelations,
    List<EndpointFlow> endpointFlows,
    List<CommandFlow> commandFlows,
    List<MessagingFlow> messagingFlows,
    List<BpmnFlow> bpmnFlows,
    List<ArchitectureInfo> architectureInfos,
    boolean fullDependencyMode
) {}
