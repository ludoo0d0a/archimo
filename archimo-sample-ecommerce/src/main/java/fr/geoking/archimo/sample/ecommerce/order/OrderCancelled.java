package fr.geoking.archimo.sample.ecommerce.order;

import java.util.UUID;

import org.jmolecules.events.types.DomainEvent;

/** Domain event: an order was cancelled. */
public record OrderCancelled(UUID orderId, String reason) implements DomainEvent {}
