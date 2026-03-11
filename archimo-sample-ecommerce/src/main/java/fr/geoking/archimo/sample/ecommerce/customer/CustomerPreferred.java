package fr.geoking.archimo.sample.ecommerce.customer;

import org.jmolecules.events.types.DomainEvent;

/** Internal event: customer preference updated (used within customer module). */
public record CustomerPreferred(String customerId, String preferenceKey, String value) implements DomainEvent {}
