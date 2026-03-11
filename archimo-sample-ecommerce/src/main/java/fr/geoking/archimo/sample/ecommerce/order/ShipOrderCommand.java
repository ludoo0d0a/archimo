package fr.geoking.archimo.sample.ecommerce.order;

import java.util.UUID;

/** Command: ship an order. */
public record ShipOrderCommand(UUID orderId, String trackingNumber) {}
