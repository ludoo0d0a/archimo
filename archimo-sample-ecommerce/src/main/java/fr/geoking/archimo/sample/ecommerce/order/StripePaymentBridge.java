package fr.geoking.archimo.sample.ecommerce.order;

import fr.geoking.archimo.sample.stripe.StripeApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * Calls the Stripe Feign {@link StripeApiClient} during checkout when {@code stripe.secret-key} is set
 * (read-only balance probe; failures are logged and do not block the domain flow).
 */
@Service
public class StripePaymentBridge {

    private static final Logger log = LoggerFactory.getLogger(StripePaymentBridge.class);

    private final StripeApiClient stripe;
    private final Environment environment;

    public StripePaymentBridge(StripeApiClient stripe, Environment environment) {
        this.stripe = stripe;
        this.environment = environment;
    }

    public void probeStripeWhenPaying() {
        String key = environment.getProperty("stripe.secret-key");
        if (key == null || key.isBlank()) {
            return;
        }
        try {
            var balance = stripe.retrieveBalance();
            log.debug("Stripe balance reachable, object={}", balance.object());
        } catch (Exception e) {
            log.warn("Stripe balance probe failed (payment flow continues): {}", e.toString());
        }
    }
}
