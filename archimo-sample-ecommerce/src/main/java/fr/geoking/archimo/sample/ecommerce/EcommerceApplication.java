package fr.geoking.archimo.sample.ecommerce;

import fr.geoking.archimo.sample.stripe.StripeApiClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * Sample Spring Modulith application: a simple e-commerce shop.
 * Modules: order, catalog, customer.
 */
@SpringBootApplication
@EnableFeignClients(clients = StripeApiClient.class)
public class EcommerceApplication {

    public static void main(String[] args) {
        SpringApplication.run(EcommerceApplication.class, args);
    }
}
