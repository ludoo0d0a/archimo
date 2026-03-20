package fr.geoking.archimo.sample.ecommerce.customer;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import org.openapitools.client.ApiException;
import org.openapitools.client.api.ProfilesApi;
import org.openapitools.client.model.Profile;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Resolves a Gravatar avatar URL for an email using the OpenAPI-generated OkHttp {@link ProfilesApi}
 * (beans are only present when {@code gravatar.api-key} is configured).
 */
@Service
public class CustomerGravatarBridge {

    private final ProfilesApi profilesApi;

    public CustomerGravatarBridge(ObjectProvider<ProfilesApi> profilesApiProvider) {
        this.profilesApi = profilesApiProvider.getIfAvailable();
    }

    /**
     * @return avatar URI when Gravatar is configured and the profile exists; empty otherwise
     */
    public Optional<URI> resolveAvatarForEmail(String email) {
        if (profilesApi == null || email == null || email.isBlank()) {
            return Optional.empty();
        }
        try {
            Profile profile = profilesApi.getProfileById(sha256HexOfEmail(email));
            return Optional.ofNullable(profile.getAvatarUrl());
        } catch (ApiException e) {
            return Optional.empty();
        }
    }

    private static String sha256HexOfEmail(String email) {
        String normalized = email.trim().toLowerCase();
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
