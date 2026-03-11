package fr.geoking.archimo.sample.ecommerce.catalog;

import java.util.UUID;

/** Domain event: stock was reserved for an order. */
public record StockReserved(UUID orderId, String productId, int quantity) {}
