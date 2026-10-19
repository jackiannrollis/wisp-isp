package com.corebalt.isp.network.infrastructure;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * RouterOS v7 REST over the WireGuard tunnel (N6): {@code GET /rest/system/resource} with basic
 * auth. Plain HTTP on the tunnel address; WireGuard already encrypts the path, and the router's www
 * service only listens on the tunnel. RouterOS returns every value as a string.
 */
@Component
public class RouterOsClient {

    private static final Pattern UPTIME = Pattern.compile("(\\d+)([wdhms])");

    /** What {@code /system/resource} reports. */
    public record Resource(String version, String boardName, Long uptimeSeconds, Integer cpuLoad, Long freeMemory,
                           Long totalMemory) {}

    /** Not reachable, refused the login, or answered something else. */
    public static class PollFailure extends Exception {
        public PollFailure(final String message) {
            super(message);
        }
    }

    private final HttpClient http;
    private final JsonMapper json;
    private final String scheme;
    private final int port;
    private final Duration timeout;

    RouterOsClient(
            final JsonMapper json,
            @Value("${isp.worker.routeros.scheme:http}") final String scheme,
            @Value("${isp.worker.routeros.port:80}") final int port,
            @Value("${isp.worker.routeros.timeout:5s}") final Duration timeout) {
        this.json = json;
        this.scheme = scheme;
        this.port = port;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public Resource resource(final String tunnelIp, final String username, final String password) throws PollFailure {
        final HttpRequest request = HttpRequest.newBuilder(URI.create(scheme + "://" + tunnelIp + ":" + port + "/rest/system/resource"))
                .timeout(timeout)
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                        (username + ":" + password).getBytes(StandardCharsets.UTF_8)))
                .GET()
                .build();
        final HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new PollFailure("unreachable: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PollFailure("interrupted");
        }
        if (response.statusCode() == 401) {
            throw new PollFailure("REST login refused (401)");
        }
        if (response.statusCode() != 200) {
            throw new PollFailure("HTTP " + response.statusCode());
        }
        try {
            final JsonNode body = json.readTree(response.body());
            return new Resource(text(body, "version"), text(body, "board-name"), uptime(text(body, "uptime")),
                    integer(text(body, "cpu-load")), number(text(body, "free-memory")), number(text(body, "total-memory")));
        } catch (RuntimeException e) {
            throw new PollFailure("unreadable answer");
        }
    }

    /** RouterOS uptime such as {@code 1w2d3h4m5s}. */
    static Long uptime(final String text) {
        if (text == null) {
            return null;
        }
        long seconds = 0;
        final Matcher matcher = UPTIME.matcher(text);
        while (matcher.find()) {
            final long value = Long.parseLong(matcher.group(1));
            seconds += switch (matcher.group(2)) {
                case "w" -> value * 604_800;
                case "d" -> value * 86_400;
                case "h" -> value * 3_600;
                case "m" -> value * 60;
                default -> value;
            };
        }
        return seconds;
    }

    private static String text(final JsonNode body, final String field) {
        final JsonNode value = body.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static Long number(final String text) {
        return text == null || text.isBlank() ? null : Long.valueOf(text.strip());
    }

    private static Integer integer(final String text) {
        return text == null || text.isBlank() ? null : Integer.valueOf(text.strip());
    }
}
