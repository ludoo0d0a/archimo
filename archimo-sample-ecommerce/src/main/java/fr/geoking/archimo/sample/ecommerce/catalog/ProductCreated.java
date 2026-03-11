package fr.geoking.archimo.sample.ecommerce.catalog;

import org.jmolecules.event.types.DomainEvent;

/** Domain event: a new product was created in the catalog. */
public record ProductCreated(String productId, String name, double price) implements DomainEvent {}
