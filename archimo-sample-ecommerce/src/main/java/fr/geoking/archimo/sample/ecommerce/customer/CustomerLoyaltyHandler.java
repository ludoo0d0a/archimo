package fr.geoking.archimo.sample.ecommerce.customer;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Internal listener: records loyalty points (within customer module). */
@Component
public class CustomerLoyaltyHandler {

    @EventListener
    public void onLoyaltyPointsEarned(LoyaltyPointsEarned event) {
        // Persist or aggregate points (internal to customer module)
    }
}
