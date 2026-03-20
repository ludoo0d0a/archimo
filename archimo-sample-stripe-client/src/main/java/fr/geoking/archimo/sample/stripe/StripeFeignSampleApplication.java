package fr.geoking.archimo.sample.stripe;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * Runnable sample: OpenFeign client against Stripe's HTTP API (balance, customers).
 */
@SpringBootApplication
@EnableFeignClients
public class StripeFeignSampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(StripeFeignSampleApplication.class, args);
    }
}
