package fr.geoking.archimo.extract.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * HTTP endpoint mapped to a controller method.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record EndpointFlow(
        String httpMethod,
        String path,
        String controllerClass,
        String controllerMethod
) {}

