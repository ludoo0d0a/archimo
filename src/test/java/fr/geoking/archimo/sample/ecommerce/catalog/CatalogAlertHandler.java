package fr.geoking.archimo.sample.ecommerce.catalog;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Internal listener: handles low-stock alerts within catalog. */
@Component
public class CatalogAlertHandler {

    @EventListener
    public void onStockLow(StockLow event) {
        // Trigger reorder or alert (internal to catalog module)
    }
}
