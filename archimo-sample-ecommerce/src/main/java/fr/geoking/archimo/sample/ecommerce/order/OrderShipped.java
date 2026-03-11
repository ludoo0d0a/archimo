package fr.geoking.archimo.sample.ecommerce.order;

import java.util.UUID;

import org.jmolecules.events.types.DomainEvent;

/** Domain event: an order was shipped. */
public record OrderShipped(UUID orderId, String trackingNumber) implements DomainEvent {}
