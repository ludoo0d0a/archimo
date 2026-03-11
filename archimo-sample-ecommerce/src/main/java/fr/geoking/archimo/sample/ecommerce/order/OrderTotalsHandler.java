package fr.geoking.archimo.sample.ecommerce.order;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Internal listener: recalculates order totals when lines are added. */
@Component
public class OrderTotalsHandler {

    @EventListener
    public void onOrderLineAdded(OrderLineAdded event) {
        // Recalculate order total (internal to order module)
    }
}
