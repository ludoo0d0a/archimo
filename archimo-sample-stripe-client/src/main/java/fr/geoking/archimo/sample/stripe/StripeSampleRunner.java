package fr.geoking.archimo.sample.stripe;

import fr.geoking.archimo.sample.stripe.dto.CustomerJson;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * On startup, calls a few Stripe endpoints when {@code stripe.secret-key} is set.
 */
@Component
public class StripeSampleRunner implements CommandLineRunner {

    private final StripeApiClient stripe;

    @Value("${stripe.secret-key:}")
    private String secretKey;

    public StripeSampleRunner(StripeApiClient stripe) {
        this.stripe = stripe;
    }

    @Override
    public void run(String... args) throws Exception {
        if (secretKey == null || secretKey.isBlank()) {
            System.err.println("Set STRIPE_SECRET_KEY or stripe.secret-key to call Stripe.");
            return;
        }

        var balance = stripe.retrieveBalance();
        System.out.println("Balance object=" + balance.object() + " livemode=" + balance.livemode());

        var customers = stripe.listCustomers(3);
        System.out.println("Customers (up to 3):");
        for (CustomerJson c : customers.data()) {
            System.out.println("  " + c.id() + " email=" + c.email());
        }

        if (args.length > 0 && !args[0].isBlank()) {
            var one = stripe.retrieveCustomer(args[0].trim());
            System.out.println("Customer " + one.id() + " email=" + one.email());
        }
    }
}
