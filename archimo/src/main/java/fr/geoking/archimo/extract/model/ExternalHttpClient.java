package fr.geoking.archimo.extract.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Outbound HTTP client usage: Feign, WebClient, RestTemplate, OpenAPI Generator clients, etc.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ExternalHttpClient(
    String clientKind,
    String declaringClass,
    String detail
) {}
