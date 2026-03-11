package fr.geoking.archimo.sample.ecommerce.catalog;

/** Internal event: stock level is low (used within catalog for alerts). */
public record StockLow(String productId, int remainingQuantity) {}
