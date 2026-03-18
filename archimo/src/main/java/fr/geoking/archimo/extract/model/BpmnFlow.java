package fr.geoking.archimo.extract.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** Represents a BPMN flow (Flowable, Camunda, Activiti). */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record BpmnFlow(
    String engine, // flowable, camunda, activiti
    String processId,
    String stepName,
    String delegateBean, // bean name or class name
    List<String> nextSteps
) {}
