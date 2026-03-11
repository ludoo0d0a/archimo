package fr.geoking.archimo.sample.ecommerce.customer;

/** Command: update a customer's address. */
public record UpdateAddressCommand(String customerId, String addressId, String newAddress) {}
