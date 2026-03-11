package fr.geoking.archimo.sample.ecommerce.order;

import fr.geoking.archimo.sample.ecommerce.catalog.StockReserved;
import fr.geoking.archimo.sample.ecommerce.catalog.ProductCreated;
import fr.geoking.archimo.sample.ecommerce.customer.AddressUpdated;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class OrderService {

    private final ApplicationEventPublisher events;

    public OrderService(ApplicationEventPublisher events) {
        this.events = events;
    }

    public void placeOrder(String productId, int quantity) {
        UUID orderId = UUID.randomUUID();
        events.publishEvent(new OrderCreated(orderId, productId, quantity));
        events.publishEvent(new OrderLineAdded(orderId, productId, quantity, 0.0));
    }

    public void payOrder(UUID orderId, String paymentId, double amount) {
        events.publishEvent(new OrderPaid(orderId, paymentId, amount));
        events.publishEvent(new OrderValidated(orderId, amount));
    }

    public void shipOrder(UUID orderId, String trackingNumber) {
        events.publishEvent(new OrderShipped(orderId, trackingNumber));
    }

    public void cancelOrder(UUID orderId, String reason) {
        events.publishEvent(new OrderCancelled(orderId, reason));
    }

    @EventListener
    public void onStockReserved(StockReserved event) {
        // Confirm reservation (cross-module: catalog → order)
    }

    @EventListener
    public void onProductCreated(ProductCreated event) {
        // Optionally index for order search (cross-module: catalog → order)
    }

    @EventListener
    public void onAddressUpdated(AddressUpdated event) {
        // Update default shipping address for orders (cross-module: customer → order)
    }
}
