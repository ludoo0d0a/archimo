package fr.geoking.archimo.sample.ecommerce.customer;

/** Domain event: customer address was updated. */
public record AddressUpdated(String customerId, String addressId, String newAddress) {}
