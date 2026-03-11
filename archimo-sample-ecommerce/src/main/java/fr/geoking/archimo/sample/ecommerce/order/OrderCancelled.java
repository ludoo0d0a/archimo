package fr.geoking.archimo.sample.ecommerce.order;

import java.util.UUID;

/** Domain event: an order was cancelled. */
public record OrderCancelled(UUID orderId, String reason) {}
