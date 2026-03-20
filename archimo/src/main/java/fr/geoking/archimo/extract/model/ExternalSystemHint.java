package fr.geoking.archimo.extract.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Inferred external dependency (database, broker, SaaS, cloud API, proxy, etc.) from images, env, or hosts.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ExternalSystemHint(
    /**
     * DATABASE, OBJECT_STORAGE, MESSAGE_BUS_KAFKA, MESSAGE_BUS_JMS, HTTP_GATEWAY, REVERSE_PROXY,
     * CACHE, SEARCH, CLOUD_PROVIDER, SAAS_HTTP, GENERIC
     */
    String category,
    String label,
    String evidence,
    String sourcePath
) {}
