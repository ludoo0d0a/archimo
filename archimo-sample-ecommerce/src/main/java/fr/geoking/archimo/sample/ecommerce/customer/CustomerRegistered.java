package fr.geoking.archimo.sample.ecommerce.customer;

import org.jmolecules.event.types.DomainEvent;

/** Domain event: a new customer registered. */
public record CustomerRegistered(String customerId, String email) implements DomainEvent {}
