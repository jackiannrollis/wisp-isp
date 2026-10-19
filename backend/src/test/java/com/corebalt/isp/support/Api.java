package com.corebalt.isp.support;

import java.io.IOException;
import com.corebalt.isp.shared.security.Permissions;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * HTTP calls to the running test server as a staff member of one tenant: an ACTIVE staff row is
 * created (token version 0) and its access token is signed with the test JVM's key (ADR 0010).
 */
public final class Api {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http = HttpClient.newHttpClient();
    private final int port;
    private final String bearer;

    private final UUID staffId;

    /** Staff of the tenant holding every permission code (A2). */
    public Api(final int port, final UUID tenantId) {
        this(port, tenantId, Permissions.ALL.toArray(String[]::new));
    }

    public Api(final int port, final UUID tenantId, final String... permissions) {
        this.port = port;
        this.staffId = TestStaff.create(tenantId);
        this.bearer = TestTokens.staff(staffId, tenantId, permissions);
    }

    /** With a ready-made bearer token (portal, platform or impersonation tests). */
    public Api(final int port, final String bearer) {
        this.port = port;
        this.staffId = null;
        this.bearer = bearer;
    }

    public UUID staffId() {
        return staffId;
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

    public Response delete(final String path) {
        return send(request(path).DELETE());
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

    public record Response(int status, String body, HttpResponse<String> raw) {

        public JsonNode json() {
            return JSON.readTree(body);
        }

        public String header(final String name) {
            return raw.headers().firstValue(name).orElse(null);
        }
    }
}
