package fr.geoking.archimo.sample.ecommerce.order;

import java.util.UUID;

/** Command: cancel an order. */
public record CancelOrderCommand(UUID orderId, String reason) {}
