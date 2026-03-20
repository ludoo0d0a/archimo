package fr.geoking.archimo.sample.ecommerce.customer;

import fr.geoking.archimo.sample.ecommerce.order.OrderShipped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
public class CustomerService {

    private static final Logger log = LoggerFactory.getLogger(CustomerService.class);

    private final ApplicationEventPublisher events;
    private final CustomerGravatarBridge gravatarBridge;

    public CustomerService(ApplicationEventPublisher events, CustomerGravatarBridge gravatarBridge) {
        this.events = events;
        this.gravatarBridge = gravatarBridge;
    }

    public boolean exists(String customerId) {
        return true;
    }

    public void register(String customerId, String email) {
        events.publishEvent(new CustomerRegistered(customerId, email));
        gravatarBridge
                .resolveAvatarForEmail(email)
                .ifPresent(uri -> log.debug("Gravatar avatar for {}: {}", email, uri));
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

    public void handle(RegisterCustomerCommand cmd) {
        register(cmd.customerId(), cmd.email());
    }

    public void handle(UpdateAddressCommand cmd) {
        updateAddress(cmd.customerId(), cmd.addressId(), cmd.newAddress());
    }

    @EventListener
    public void onOrderShipped(OrderShipped event) {
        // Notify customer (cross-module: order → customer)
    }
}
