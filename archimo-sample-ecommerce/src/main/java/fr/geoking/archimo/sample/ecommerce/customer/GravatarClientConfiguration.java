package fr.geoking.archimo.sample.ecommerce.customer;

import org.openapitools.client.ApiClient;
import org.openapitools.client.api.ProfilesApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnExpression("!'${gravatar.api-key:}'.isEmpty()")
class GravatarClientConfiguration {

    @Bean
    ApiClient gravatarApiClient(@Value("${gravatar.api-key}") String apiKey) {
        ApiClient client = new ApiClient();
        client.setBearerToken(apiKey.trim());
        return client;
    }

    @Bean
    ProfilesApi gravatarProfilesApi(ApiClient gravatarApiClient) {
        return new ProfilesApi(gravatarApiClient);
    }
}
