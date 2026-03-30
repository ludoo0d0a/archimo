package fr.geoking.archimo.extract.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import fr.geoking.archimo.extract.model.ModuleEvents;

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
    List<OpenApiSpecFile> openApiSpecFiles,
    List<ExternalHttpClient> externalHttpClients,
    /** Runtime topology from Docker / Kubernetes manifests under the project. */
    InfrastructureTopology infrastructureTopology,
    /** Fully qualified main application class when discovered, else null. */
    String applicationMainClass,
    boolean fullDependencyMode,
    /** jMolecules / ArchUnit build hints, typed elements, relations, static findings. */
    FrameworkDesignInsights frameworkDesignInsights
) {}
