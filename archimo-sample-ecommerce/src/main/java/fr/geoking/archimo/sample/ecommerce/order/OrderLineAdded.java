package fr.geoking.archimo.sample.ecommerce.order;

import java.util.UUID;

import org.jmolecules.events.types.DomainEvent;

/** Internal event: a line was added to an order (used within order module for totals). */
public record OrderLineAdded(UUID orderId, String productId, int quantity, double unitPrice) implements DomainEvent {}
