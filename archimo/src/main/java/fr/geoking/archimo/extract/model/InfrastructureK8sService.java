package fr.geoking.archimo.extract.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record InfrastructureK8sService(
    String name,
    String namespace,
    String type,
    List<String> ports,
    String sourcePath
) {}
