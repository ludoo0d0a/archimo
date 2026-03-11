package fr.geoking.archimo.sample.ecommerce.order;

import java.util.UUID;

import org.jmolecules.events.types.DomainEvent;

/** Internal event: order validated (used within order module e.g. for invoicing). */
public record OrderValidated(UUID orderId, double total) implements DomainEvent {}
