package fr.geoking.archimo.extract.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** Represents a flow: one module publishes an event, one or more modules listen. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record EventFlow(
    String eventType,
    String publisherModule,
    List<String> listenerModules
) {}

