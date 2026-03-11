package fr.geoking.archimo.sample.ecommerce.customer;

/** Internal event: loyalty points earned (used within customer for analytics). */
public record LoyaltyPointsEarned(String customerId, int points, String reason) {}
