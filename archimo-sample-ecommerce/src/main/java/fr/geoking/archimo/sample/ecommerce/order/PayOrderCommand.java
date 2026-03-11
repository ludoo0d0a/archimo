package fr.geoking.archimo.sample.ecommerce.order;

import java.util.UUID;

/** Command: pay an order. */
public record PayOrderCommand(UUID orderId, String paymentId, double amount) {}
