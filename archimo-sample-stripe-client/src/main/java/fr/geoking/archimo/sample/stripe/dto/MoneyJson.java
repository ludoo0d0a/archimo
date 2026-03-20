package fr.geoking.archimo.sample.stripe.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MoneyJson(Long amount, String currency) {}
