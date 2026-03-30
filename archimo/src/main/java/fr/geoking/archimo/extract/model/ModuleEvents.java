package fr.geoking.archimo.extract.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ModuleEvents(
    String moduleName,
    String basePackage,
    List<String> publishedEvents,
    List<String> eventsListenedTo
) {}
