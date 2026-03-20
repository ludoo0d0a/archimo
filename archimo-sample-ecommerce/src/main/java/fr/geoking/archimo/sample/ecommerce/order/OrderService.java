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
    private final StripePaymentBridge stripePaymentBridge;

    public OrderService(ApplicationEventPublisher events, StripePaymentBridge stripePaymentBridge) {
        this.events = events;
        this.stripePaymentBridge = stripePaymentBridge;
    }

    public void placeOrder(String productId, int quantity) {
        UUID orderId = UUID.randomUUID();
        events.publishEvent(new OrderCreated(orderId, productId, quantity));
        events.publishEvent(new OrderLineAdded(orderId, productId, quantity, 0.0));
    }

    public void payOrder(UUID orderId, String paymentId, double amount) {
        stripePaymentBridge.probeStripeWhenPaying();
        events.publishEvent(new OrderPaid(orderId, paymentId, amount));
        events.publishEvent(new OrderValidated(orderId, amount));
    }

    public void shipOrder(UUID orderId, String trackingNumber) {
        events.publishEvent(new OrderShipped(orderId, trackingNumber));
    }

    public void cancelOrder(UUID orderId, String reason) {
        events.publishEvent(new OrderCancelled(orderId, reason));
    }

    public void handle(PlaceOrderCommand cmd) {
        placeOrder(cmd.productId(), cmd.quantity());
    }

    public void handle(PayOrderCommand cmd) {
        payOrder(cmd.orderId(), cmd.paymentId(), cmd.amount());
    }

    public void handle(ShipOrderCommand cmd) {
        shipOrder(cmd.orderId(), cmd.trackingNumber());
    }

    public void handle(CancelOrderCommand cmd) {
        cancelOrder(cmd.orderId(), cmd.reason());
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
