package fr.geoking.archimo.extract.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Runtime topology inferred from Dockerfiles, Compose files, and Kubernetes manifests.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record InfrastructureTopology(
    List<InfrastructureFileHit> files,
    List<InfrastructureContainer> containers,
    List<InfrastructureK8sService> kubernetesServices,
    List<InfrastructureIngress> ingresses,
    List<ExternalSystemHint> externalSystems
) {
    public static InfrastructureTopology empty() {
        return new InfrastructureTopology(List.of(), List.of(), List.of(), List.of(), List.of());
    }

    public boolean isEmpty() {
        return files.isEmpty() && containers.isEmpty() && kubernetesServices.isEmpty()
                && ingresses.isEmpty() && externalSystems.isEmpty();
    }
}
