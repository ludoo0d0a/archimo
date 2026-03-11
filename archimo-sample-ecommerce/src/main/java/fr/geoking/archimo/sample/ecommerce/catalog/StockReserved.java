package fr.geoking.archimo.sample.ecommerce.catalog;

import java.util.UUID;

import org.jmolecules.events.types.DomainEvent;

/** Domain event: stock was reserved for an order. */
public record StockReserved(UUID orderId, String productId, int quantity) implements DomainEvent {}
