package com.corebalt.isp.notifications.infrastructure;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Africa's Talking bulk SMS ({@code POST /version1/messaging}, form-encoded, {@code apiKey}
 * header), one recipient per call, always in E.164. The request shape is pinned by
 * AfricasTalkingClientContractTest. Nothing here logs the message, the number or the key.
 */
@Component
public class AfricasTalkingClient {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final JsonMapper json;
    private final String sandboxUrl;
    private final String productionUrl;

    public AfricasTalkingClient(
            final JsonMapper json,
            @Value("${isp.sms.at.sandbox-url:https://api.sandbox.africastalking.com}") final String sandboxUrl,
            @Value("${isp.sms.at.production-url:https://api.africastalking.com}") final String productionUrl) {
        this.json = json;
        this.sandboxUrl = sandboxUrl;
        this.productionUrl = productionUrl;
    }

    /** The per-recipient answer: {@code statusCode} 100-102 sent, 4xx/5xx refused (see SmsSender). */
    public record Result(int statusCode, String status, String messageId) {}

    /** A call that got no per-recipient answer: {@code httpStatus} 0 for a timeout or network error. */
    public static class TransportFailure extends RuntimeException {

        private final int httpStatus;

        TransportFailure(final int httpStatus, final String message) {
            super(message);
            this.httpStatus = httpStatus;
        }

        public int httpStatus() {
            return httpStatus;
        }
    }

    public Result send(
            final boolean sandbox, final String username, final String apiKey, final String senderId,
            final String toE164, final String message) {
        final Map<String, String> form = new LinkedHashMap<>();
        form.put("username", username);
        form.put("to", toE164);
        form.put("message", message);
        form.put("from", senderId);
        final String body = form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        final HttpRequest request = HttpRequest.newBuilder(URI.create((sandbox ? sandboxUrl : productionUrl) + "/version1/messaging"))
                .timeout(Duration.ofSeconds(20))
                .header("apiKey", apiKey)
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        final HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new TransportFailure(0, "timeout");
        } catch (java.io.IOException e) {
            throw new TransportFailure(0, "unreachable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransportFailure(0, "interrupted");
        }
        if (response.statusCode() / 100 != 2) {
            throw new TransportFailure(response.statusCode(), "HTTP " + response.statusCode());
        }
        final JsonNode recipient;
        try {
            recipient = json.readTree(response.body()).path("SMSMessageData").path("Recipients").path(0);
        } catch (RuntimeException e) {
            throw new TransportFailure(response.statusCode(), "unreadable response");
        }
        if (recipient.isMissingNode()) {
            throw new TransportFailure(response.statusCode(), "no recipient in response");
        }
        return new Result(recipient.path("statusCode").asInt(0), recipient.path("status").asString(""),
                recipient.path("messageId").asString(null));
    }
}
