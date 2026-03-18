package fr.geoking.archimo.extract.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** Represents a messaging flow (Kafka, JMS, etc.). */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record MessagingFlow(
    String technology,
    String destination, // topic or queue name
    String publisher,
    List<String> subscribers
) {}
