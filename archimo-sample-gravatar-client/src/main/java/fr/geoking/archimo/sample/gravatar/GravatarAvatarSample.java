package fr.geoking.archimo.sample.gravatar;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import okhttp3.Request;
import okhttp3.Response;
import org.openapitools.client.ApiClient;
import org.openapitools.client.ApiException;
import org.openapitools.client.api.ProfilesApi;
import org.openapitools.client.model.Profile;

/**
 * Uses the OpenAPI-generated OkHttp {@link ApiClient} to load a Gravatar profile, then downloads the avatar
 * image with the same {@link okhttp3.OkHttpClient} and writes a tiny HTML preview.
 * <p>
 * Requires {@code GRAVATAR_API_KEY} (Bearer token from the
 * <a href="https://gravatar.com/developers">Gravatar developer dashboard</a>).
 * Pass the account email as the first argument (SHA-256 hash is computed per
 * <a href="https://docs.gravatar.com/rest/api-data-specifications/">Gravatar docs</a>).
 */
public final class GravatarAvatarSample {

    private GravatarAvatarSample() {}

    public static void main(String[] args) throws ApiException, IOException {
        String apiKey = System.getenv("GRAVATAR_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("Set GRAVATAR_API_KEY (Bearer token from gravatar.com/developers).");
            System.exit(1);
        }
        if (args.length < 1 || args[0].isBlank()) {
            System.err.println("Usage: GravatarAvatarSample <email>");
            System.exit(1);
        }

        String email = args[0].trim();
        String hash = sha256HexOfEmail(email);

        ApiClient apiClient = new ApiClient();
        apiClient.setBearerToken(apiKey.trim());

        ProfilesApi profiles = new ProfilesApi(apiClient);
        Profile profile = profiles.getProfileById(hash);

        URI avatarUri = profile.getAvatarUrl();
        System.out.println("displayName=" + profile.getDisplayName());
        System.out.println("avatarUrl=" + avatarUri);

        Path imagePath = Path.of("gravatar-avatar.image");
        Path htmlPath = Path.of("gravatar-preview.html");

        Request imageRequest = new Request.Builder().url(avatarUri.toString()).build();
        try (Response response = apiClient.getHttpClient().newCall(imageRequest).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                System.err.println("Avatar download failed: HTTP " + response.code());
                System.exit(2);
            }
            byte[] bytes = response.body().bytes();
            Files.write(imagePath, bytes);
            System.out.println("Saved image bytes to " + imagePath.toAbsolutePath());
        }

        String html =
                """
                <!DOCTYPE html>
                <html lang="en">
                <head><meta charset="utf-8"><title>Gravatar sample</title></head>
                <body>
                <h1>%s</h1>
                <p>Email hash (SHA-256): %s</p>
                <img src="%s" width="256" height="256" alt="%s"/>
                </body>
                </html>
                """
                        .formatted(
                                escapeHtml(profile.getDisplayName()),
                                escapeHtml(hash),
                                escapeHtml(avatarUri.toString()),
                                escapeHtml(profile.getAvatarAltText()));
        Files.writeString(htmlPath, html, StandardCharsets.UTF_8);
        System.out.println("Open in a browser: " + htmlPath.toAbsolutePath());
    }

    static String sha256HexOfEmail(String email) {
        String normalized = email.trim().toLowerCase();
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String escapeHtml(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
