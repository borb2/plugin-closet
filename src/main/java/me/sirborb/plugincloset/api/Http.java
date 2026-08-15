package me.sirborb.plugincloset.api;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * The one {@link HttpClient} both source clients share.
 *
 * <p>Redirects are followed because Hangar's download route answers 303. NORMAL still
 * refuses an HTTPS to HTTP downgrade, which is what we want for something that writes
 * jars into the plugins folder.
 */
public final class Http {

    /** Refuse absurd search responses long before they become a memory problem. */
    private static final int MAX_JSON_BYTES = 8 * 1024 * 1024;

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private Http() {
    }

    public static HttpClient client() {
        return CLIENT;
    }

    /** GET the URL and parse the body as JSON. Non-2xx becomes a failed future. */
    public static CompletableFuture<Object> getJson(String url, String userAgent, String bearerToken) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .GET();
        if (bearerToken != null && !bearerToken.isBlank()) {
            b.header("Authorization", "Bearer " + bearerToken);
        }
        return CLIENT.sendAsync(b.build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() / 100 != 2) {
                        throw new java.util.concurrent.CompletionException(
                                new IOException("HTTP " + resp.statusCode() + " from " + url
                                        + ": " + truncate(resp.body())));
                    }
                    String body = resp.body();
                    if (body.length() > MAX_JSON_BYTES) {
                        throw new java.util.concurrent.CompletionException(
                                new IOException("response from " + url + " exceeded "
                                        + MAX_JSON_BYTES + " bytes"));
                    }
                    return Json.parse(body);
                });
    }

    private static String truncate(String body) {
        if (body == null) return "";
        return body.length() <= 200 ? body : body.substring(0, 200) + "...";
    }
}
