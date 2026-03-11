package fr.geoking.archimo.sample.ecommerce.order.invoice;

import fr.geoking.archimo.sample.ecommerce.order.OrderValidated;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Generates and manages invoices within the order context.
 * Nested module: order::invoice. Listens to OrderValidated (internal order event).
 */
@Service
public class InvoiceService {

    @EventListener
    public void onOrderValidated(OrderValidated event) {
        createInvoice(event.orderId());
    }

    public String createInvoice(UUID orderId) {
        return "INV-" + orderId.toString().substring(0, 8);
    }
}
