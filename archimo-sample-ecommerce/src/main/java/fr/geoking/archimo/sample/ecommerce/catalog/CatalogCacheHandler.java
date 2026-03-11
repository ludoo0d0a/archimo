package fr.geoking.archimo.sample.ecommerce.catalog;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Internal listener: invalidates cache when price changes. */
@Component
public class CatalogCacheHandler {

    @EventListener
    public void onPriceUpdated(PriceUpdated event) {
        // Invalidate product cache (internal to catalog module)
    }
}
