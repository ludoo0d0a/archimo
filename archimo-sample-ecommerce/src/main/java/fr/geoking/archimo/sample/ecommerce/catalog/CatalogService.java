package fr.geoking.archimo.sample.ecommerce.catalog;

import fr.geoking.archimo.sample.ecommerce.order.OrderCreated;
import fr.geoking.archimo.sample.ecommerce.order.OrderCancelled;
import fr.geoking.archimo.sample.ecommerce.order.OrderPaid;
import fr.geoking.archimo.sample.ecommerce.customer.CustomerRegistered;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class CatalogService {

    private final ApplicationEventPublisher events;

    public CatalogService(ApplicationEventPublisher events) {
        this.events = events;
    }

    @EventListener
    public void onOrderCreated(OrderCreated event) {
        events.publishEvent(new StockReserved(event.orderId(), event.productId(), event.quantity()));
    }

    @EventListener
    public void onOrderPaid(OrderPaid event) {
        // Confirm reservation (cross-module: order → catalog)
    }

    @EventListener
    public void onOrderCancelled(OrderCancelled event) {
        // Release reserved stock (cross-module: order → catalog)
    }

    @EventListener
    public void onCustomerRegistered(CustomerRegistered event) {
        // Optionally create wishlist (cross-module: customer → catalog)
    }

    public void createProduct(String productId, String name, double price) {
        events.publishEvent(new ProductCreated(productId, name, price));
    }

    public void notifyStockLow(String productId, int remaining) {
        events.publishEvent(new StockLow(productId, remaining));
    }

    public void updatePrice(String productId, double oldPrice, double newPrice) {
        events.publishEvent(new PriceUpdated(productId, oldPrice, newPrice));
    }

    public void handle(CreateProductCommand cmd) {
        createProduct(cmd.productId(), cmd.name(), cmd.price());
    }

    public void handle(ReserveStockCommand cmd) {
        // In a real app this would reserve stock; we publish StockReserved for order flow
        events.publishEvent(new StockReserved(cmd.orderId(), cmd.productId(), cmd.quantity()));
    }
}
