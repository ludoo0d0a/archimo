package fr.geoking.archimo.extract.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/** OpenAPI or Swagger specification file discovered on the project tree (source / resources). */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record OpenApiSpecFile(
    String relativePath,
    String specKind
) {}
