package fr.geoking.archimo.sample.stripe;

import fr.geoking.archimo.sample.stripe.dto.BalanceJson;
import fr.geoking.archimo.sample.stripe.dto.CustomerJson;
import fr.geoking.archimo.sample.stripe.dto.CustomerListJson;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(
        name = "stripe",
        url = "${stripe.api.url:https://api.stripe.com}",
        configuration = StripeFeignConfiguration.class)
public interface StripeApiClient {

    @GetMapping(value = "/v1/balance", produces = MediaType.APPLICATION_JSON_VALUE)
    BalanceJson retrieveBalance();

    @GetMapping(value = "/v1/customers/{customerId}", produces = MediaType.APPLICATION_JSON_VALUE)
    CustomerJson retrieveCustomer(@PathVariable("customerId") String customerId);

    @GetMapping(value = "/v1/customers", produces = MediaType.APPLICATION_JSON_VALUE)
    CustomerListJson listCustomers(@RequestParam(value = "limit", defaultValue = "5") int limit);
}
