package fr.geoking.archimo.sample.ecommerce.catalog;

/** Internal event: product price was updated (used within catalog for cache invalidation). */
public record PriceUpdated(String productId, double oldPrice, double newPrice) {}
