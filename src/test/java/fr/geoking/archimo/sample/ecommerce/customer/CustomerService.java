package fr.geoking.archimo.sample.ecommerce.customer;

import fr.geoking.archimo.sample.ecommerce.order.OrderShipped;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
public class CustomerService {

    private final ApplicationEventPublisher events;

    public CustomerService(ApplicationEventPublisher events) {
        this.events = events;
    }

    public boolean exists(String customerId) {
        return true;
    }

    public void register(String customerId, String email) {
        events.publishEvent(new CustomerRegistered(customerId, email));
    }

    public void updateAddress(String customerId, String addressId, String newAddress) {
        events.publishEvent(new AddressUpdated(customerId, addressId, newAddress));
    }

    public void earnLoyaltyPoints(String customerId, int points, String reason) {
        events.publishEvent(new LoyaltyPointsEarned(customerId, points, reason));
    }

    public void setPreference(String customerId, String key, String value) {
        events.publishEvent(new CustomerPreferred(customerId, key, value));
    }

    @EventListener
    public void onOrderShipped(OrderShipped event) {
        // Notify customer (cross-module: order → customer)
    }
}
