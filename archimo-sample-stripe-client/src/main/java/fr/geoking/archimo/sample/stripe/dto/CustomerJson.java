package fr.geoking.archimo.sample.stripe.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CustomerJson(String id, String object, String email) {}
