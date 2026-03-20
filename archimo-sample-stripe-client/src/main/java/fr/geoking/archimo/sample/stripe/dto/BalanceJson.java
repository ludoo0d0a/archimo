package fr.geoking.archimo.sample.stripe.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BalanceJson(String object, Boolean livemode, List<MoneyJson> available) {}
