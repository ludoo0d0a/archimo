package fr.geoking.archimo.sample.ecommerce.catalog;

/** Command: create a new product in the catalog. */
public record CreateProductCommand(String productId, String name, double price) {}
