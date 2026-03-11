package fr.geoking.archimo.sample.ecommerce.customer;

/** Command: register a new customer. */
public record RegisterCustomerCommand(String customerId, String email) {}
