package fr.geoking.archimo.extract.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** One sequence: event type with ordered participants (publisher then listeners). */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record SequenceFlow(
    String eventType,
    String publisherModule,
    List<String> listenerModules
) {}

