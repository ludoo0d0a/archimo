package fr.geoking.archimo.sample.ecommerce.customer;

import org.jmolecules.events.types.DomainEvent;

/** Domain event: customer address was updated. */
public record AddressUpdated(String customerId, String addressId, String newAddress) implements DomainEvent {}
