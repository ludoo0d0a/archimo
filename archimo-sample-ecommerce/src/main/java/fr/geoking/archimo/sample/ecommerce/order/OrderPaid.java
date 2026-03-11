package fr.geoking.archimo.sample.ecommerce.order;

import java.util.UUID;

import org.jmolecules.events.types.DomainEvent;

/** Domain event: an order was paid. */
public record OrderPaid(UUID orderId, String paymentId, double amount) implements DomainEvent {}
