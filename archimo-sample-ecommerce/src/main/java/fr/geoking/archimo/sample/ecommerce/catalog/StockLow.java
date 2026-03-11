package fr.geoking.archimo.sample.ecommerce.catalog;

import org.jmolecules.events.types.DomainEvent;

/** Internal event: stock level is low (used within catalog for alerts). */
public record StockLow(String productId, int remainingQuantity) implements DomainEvent {}
