package fr.geoking.archimo.sample.ecommerce.catalog;

/** Domain event: a new product was created in the catalog. */
public record ProductCreated(String productId, String name, double price) {}
