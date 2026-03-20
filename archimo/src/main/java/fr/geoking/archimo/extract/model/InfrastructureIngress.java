package fr.geoking.archimo.extract.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record InfrastructureIngress(
    String name,
    String namespace,
    String ingressClassName,
    List<String> hosts,
    List<String> pathHints,
    String sourcePath
) {}
