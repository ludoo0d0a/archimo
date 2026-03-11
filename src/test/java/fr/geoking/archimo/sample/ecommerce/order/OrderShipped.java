package fr.geoking.archimo.sample.ecommerce.order;

import java.util.UUID;

/** Domain event: an order was shipped. */
public record OrderShipped(UUID orderId, String trackingNumber) {}
