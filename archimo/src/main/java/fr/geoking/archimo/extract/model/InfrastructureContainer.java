package fr.geoking.archimo.extract.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A container definition from Docker Compose or a Kubernetes workload.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record InfrastructureContainer(
    String name,
    String image,
    /** Relative path to the defining file */
    String sourcePath,
    /** e.g. compose:api, k8s:Deployment/orders */
    String context,
    List<String> ports
) {}
