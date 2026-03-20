package fr.geoking.archimo.sample.stripe;

import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;

/**
 * Feign-only configuration (not a {@code @Configuration} bean: avoids duplicate registration when scanned).
 */
public class StripeFeignConfiguration {

    @Bean
    RequestInterceptor stripeBearerAuth(@Value("${stripe.secret-key:}") String secretKey) {
        return template -> {
            if (secretKey != null && !secretKey.isBlank()) {
                template.header(HttpHeaders.AUTHORIZATION, "Bearer " + secretKey.trim());
            }
        };
    }
}
