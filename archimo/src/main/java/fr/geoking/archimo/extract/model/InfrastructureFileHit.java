package fr.geoking.archimo.extract.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A Docker or Kubernetes manifest file discovered under the project tree.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record InfrastructureFileHit(
    String relativePath,
    /** e.g. DOCKERFILE, DOCKER_COMPOSE, KUBERNETES */
    String kind
) {}
