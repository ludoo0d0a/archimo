package fr.geoking.archimo.sample.ecommerce.customer;

/** Domain event: a new customer registered. */
public record CustomerRegistered(String customerId, String email) {}
