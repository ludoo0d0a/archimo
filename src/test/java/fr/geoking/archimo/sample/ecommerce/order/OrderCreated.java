package fr.geoking.archimo.sample.ecommerce.order;

import java.util.UUID;

/** Domain event: an order was created. */
public record OrderCreated(UUID orderId, String productId, int quantity) {}
