package fr.geoking.archimo.sample.ecommerce.catalog;

import java.util.UUID;

/** Command: reserve stock for an order. */
public record ReserveStockCommand(UUID orderId, String productId, int quantity) {}
