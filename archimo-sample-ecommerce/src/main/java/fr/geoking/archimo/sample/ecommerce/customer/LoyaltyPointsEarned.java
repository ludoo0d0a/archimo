package fr.geoking.archimo.sample.ecommerce.customer;

import org.jmolecules.event.types.DomainEvent;

/** Internal event: loyalty points earned (used within customer for analytics). */
public record LoyaltyPointsEarned(String customerId, int points, String reason) implements DomainEvent {}
