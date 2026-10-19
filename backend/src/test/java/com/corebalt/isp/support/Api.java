package com.corebalt.isp.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** HTTP calls to the running test server as a staff member of one tenant (stub JWT, local/test only). */
public final class Api {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http = HttpClient.newHttpClient();
    private final int port;
    private final String bearer;

    /** Staff of the tenant holding every permission code checked so far (A2). */
    public Api(final int port, final UUID tenantId) {
        this(port, tenantId, "payments.record_manual", "billing.adjust", "settings.secrets", "payments.assign", "payments.stk");
    }

    public Api(final int port, final UUID tenantId, final String... permissions) {
        this.port = port;
        final String codes = String.join(",", java.util.Arrays.stream(permissions).map(p -> "\"" + p + "\"").toList());
        this.bearer = token("{\"sub\":\"" + UUID.randomUUID() + "\",\"tenant_id\":\"" + tenantId
                + "\",\"permissions\":[" + codes + "]}");
    }

    public Response get(final String path) {
        return send(request(path).GET());
    }

    public Response post(final String path, final String json) {
        return send(request(path).header("Content-Type", "application/json").POST(body(json)));
    }

    public Response post(final String path) {
        return send(request(path).POST(HttpRequest.BodyPublishers.noBody()));
    }

    /** POST with an Idempotency-Key; {@code json} may be null for an empty body. */
    public Response postWithKey(final String path, final String key, final String json) {
        final HttpRequest.Builder request = request(path).header("Idempotency-Key", key);
        return send(json == null
                ? request.POST(HttpRequest.BodyPublishers.noBody())
                : request.header("Content-Type", "application/json").POST(body(json)));
    }

    public Response put(final String path, final String json) {
        return send(request(path).header("Content-Type", "application/json").PUT(body(json)));
    }

    public Response postCsv(final String path, final String csv) {
        return send(request(path).header("Content-Type", "text/csv").POST(body(csv)));
    }

    private HttpRequest.Builder request(final String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + bearer);
    }

    private static HttpRequest.BodyPublisher body(final String text) {
        return HttpRequest.BodyPublishers.ofString(text, StandardCharsets.UTF_8);
    }

    private Response send(final HttpRequest.Builder request) {
        try {
            final HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body(), response);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Unsigned JWT ({@code alg: none}); accepted only by the local/test stub decoder. */
    public static String token(final String claimsJson) {
        final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8))
                + "."
                + encoder.encodeToString(claimsJson.getBytes(StandardCharsets.UTF_8))
                + ".";
    }

    public record Response(int status, String body, HttpResponse<String> raw) {

        public JsonNode json() {
            return JSON.readTree(body);
        }

        public String header(final String name) {
            return raw.headers().firstValue(name).orElse(null);
        }
    }
}
