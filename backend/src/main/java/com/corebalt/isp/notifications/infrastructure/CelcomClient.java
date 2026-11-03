package com.corebalt.isp.notifications.infrastructure;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Celcom Africa SMS: JSON POST, one recipient per call, credentials in the body (never a URL).
 * https://www.celcomafrica.com/developers documents the misspelled {@code respose-code} field.
 * No response body or exception containing credentials, phone numbers or message text is logged.
 */
@Component
public class CelcomClient {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final JsonMapper json;
    private final String baseUrl;

    public CelcomClient(
            final JsonMapper json,
            @Value("${isp.sms.celcom.base-url:https://isms.celcomafrica.com}") final String baseUrl) {
        this.json = json;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    /** 200 means accepted for sending, never proof of delivery to the handset. */
    public record Result(int statusCode, String status, String messageId) {}

    public record DeliveryReport(boolean delivered, String status) {}

    /**
     * The request is documented by Celcom; the 200 / message-id / delivery-status=32 /
     * DeliveredToTerminal response was verified against the live test SMS on 2026-11-02.
     * Unknown codes and descriptions remain unconfirmed rather than guessed into terminal states.
     */
    public DeliveryReport deliveryReport(final String partnerId, final String apiKey, final String messageId) {
        final JsonNode root = post("/api/services/getdlr/", Map.of(
                "partnerID", partnerId, "apikey", apiKey, "messageID", messageId));
        final int code = root.path("response-code").asInt(root.path("respose-code").asInt(0));
        if (code == 1008) { return new DeliveryReport(false, "No delivery report"); }
        if (code != 200 || !messageId.equals(root.path("message-id").asString())) {
            throw new TransportFailure(200, "invalid delivery acknowledgement");
        }
        final int status = root.path("delivery-status").asInt(-1);
        final boolean delivered = status == 32 && "DeliveredToTerminal".equals(root.path("delivery-description").asString());
        return new DeliveryReport(delivered, delivered ? "DeliveredToTerminal" : "Celcom delivery status " + status);
    }

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
            final String partnerId, final String apiKey, final String senderId,
            final String toE164, final String message) {
        if (toE164 == null || !toE164.matches("\\+[1-9][0-9]{7,14}")) {
            return new Result(1003, "Invalid mobile number", null);
        }
        final String mobile = toE164.substring(1);
        final JsonNode root = post("/api/services/sendsms/", Map.of("partnerID", partnerId, "apikey", apiKey,
                "shortcode", senderId, "mobile", mobile, "message", message, "pass_type", "plain"));
        final JsonNode responses = root.path("responses");
        final JsonNode recipient;
        if (responses.isArray() && responses.size() == 1) {
            recipient = responses.path(0);
        } else if (root.has("response-code") || root.has("respose-code")) {
            recipient = root;
        } else {
            throw new TransportFailure(200, "unreadable response");
        }
        final int code = recipient.path("respose-code").asInt(recipient.path("response-code").asInt(0));
        final String messageId = recipient.path("messageid").asString(null);
        if (code == 200 && (!mobile.equals(recipient.path("mobile").asString())
                || messageId == null || messageId.isBlank() || messageId.length() > 200)) {
            throw new TransportFailure(200, "invalid send acknowledgement");
        }
        return new Result(code, description(code), code == 200 ? messageId : null);
    }

    private JsonNode post(final String path, final Map<String, String> payload) {
        final String body = json.writeValueAsString(payload);
        final HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
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
        try {
            return json.readTree(response.body());
        } catch (RuntimeException e) {
            throw new TransportFailure(response.statusCode(), "unreadable response");
        }
    }

    /** Provider text may echo secrets or recipients; only these stable descriptions leave the client. */
    static String description(final int code) {
        return switch (code) {
            case 200 -> "Accepted";
            case 1001 -> "Invalid sender ID";
            case 1002 -> "Network not allowed";
            case 1003 -> "Invalid mobile number";
            case 1004 -> "Low bulk credits";
            case 1006 -> "Invalid credentials";
            case 1008 -> "No delivery report";
            case 1009 -> "Unsupported data type";
            case 1010 -> "Unsupported request type";
            case 4090 -> "Internal error";
            default -> "Provider refused request";
        };
    }
}
