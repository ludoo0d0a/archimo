package fr.geoking.archimo.sample.ecommerce.customer;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Internal listener: analytics for preference changes. */
@Component
public class CustomerAnalytics {

    @EventListener
    public void onCustomerPreferred(CustomerPreferred event) {
        // Track preference changes (internal to customer module)
    }
}
