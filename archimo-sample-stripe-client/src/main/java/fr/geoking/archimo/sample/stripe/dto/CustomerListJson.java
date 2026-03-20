package fr.geoking.archimo.sample.stripe.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CustomerListJson(
        String object,
        List<CustomerJson> data,
        @JsonProperty("has_more") boolean hasMore) {}
