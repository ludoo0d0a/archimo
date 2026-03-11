package fr.geoking.archimo.sample.ecommerce.order;

/** Command: place a new order. */
public record PlaceOrderCommand(String productId, int quantity) {}
