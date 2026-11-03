package com.corebalt.isp.payments.infrastructure.daraja;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Safaricom Daraja (docs/05 M-Pesa integration): OAuth, C2B RegisterURL, STK Push, STK Push Query
 * and Transaction Status. Request shapes are pinned by DarajaClientContractTest. Nothing here logs
 * a request, response or credential; errors carry the Daraja error code only.
 *
 * <p>Daraja times are East Africa Time; STK {@code Password} is
 * {@code base64(shortcode + passkey + timestamp)} with the timestamp {@code yyyyMMddHHmmss} in
 * Africa/Nairobi; amounts are whole shillings.
 */
@Component
public class DarajaClient {

    public static final ZoneId EAT = ZoneId.of("Africa/Nairobi");
    static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final Object transportLock = new Object();
    private Transport transport = new Transport();
    private final JsonMapper json;
    private final Clock clock;
    private final String sandboxUrl;
    private final String productionUrl;
    private final Map<String, CachedToken> tokens = new ConcurrentHashMap<>();

    public DarajaClient(
            final JsonMapper json,
            final Clock clock,
            @Value("${isp.mpesa.daraja.sandbox-url:https://sandbox.safaricom.co.ke}") final String sandboxUrl,
            @Value("${isp.mpesa.daraja.production-url:https://api.safaricom.co.ke}") final String productionUrl) {
        this.json = json;
        this.clock = clock;
        this.sandboxUrl = sandboxUrl;
        this.productionUrl = productionUrl;
    }

    /** What a call needs from the tenant's settings and secrets; never logged. */
    public record Credentials(
            boolean sandbox,
            String shortcode,
            boolean till,
            String consumerKey,
            String consumerSecret,
            String passkey,
            String initiatorName,
            String securityCredential) {

        @Override
        public String toString() {
            return "Credentials[shortcode=" + shortcode + ", sandbox=" + sandbox + "]";
        }
    }

    public record StkPushResult(String merchantRequestId, String checkoutRequestId, String responseCode, String customerMessage) {}

    /** {@code resultCode} null while Daraja still processes ({@code errorCode} 500.001.1001). */
    public record StkQueryResult(String resultCode, String resultDesc, String errorCode) {

        public boolean stillProcessing() {
            return resultCode == null;
        }
    }

    public record Accepted(String conversationId, String originatorConversationId, String responseCode, String responseDescription) {}

    public String accessToken(final Credentials credentials) {
        final String cacheKey = credentials.sandbox() + ":" + credentials.consumerKey();
        final CachedToken cached = tokens.get(cacheKey);
        final Instant now = clock.instant();
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.token();
        }
        final String basic = Base64.getEncoder().encodeToString(
                (credentials.consumerKey() + ":" + credentials.consumerSecret()).getBytes(StandardCharsets.UTF_8));
        final JsonNode body = send(HttpRequest.newBuilder(uri(credentials, "/oauth/v1/generate?grant_type=client_credentials"))
                .header("Authorization", "Basic " + basic)
                .GET());
        final String token = text(body, "access_token");
        final long expiresIn = body.path("expires_in").asLong(3599);
        tokens.put(cacheKey, new CachedToken(token, now.plusSeconds(Math.max(60, expiresIn - 60))));
        return token;
    }

    /** C2B v2 RegisterURL with ResponseType Completed: if the validation URL fails, the payment completes. */
    public Accepted registerUrls(final Credentials credentials, final String confirmationUrl, final String validationUrl) {
        final Map<String, Object> request = new LinkedHashMap<>();
        request.put("ShortCode", credentials.shortcode());
        request.put("ResponseType", "Completed");
        request.put("ConfirmationURL", confirmationUrl);
        request.put("ValidationURL", validationUrl);
        final JsonNode body = post(credentials, "/mpesa/c2b/v2/registerurl", request);
        return new Accepted(null, text(body, "OriginatorCoversationID"), text(body, "ResponseCode"), text(body, "ResponseDescription"));
    }

    public StkPushResult stkPush(
            final Credentials credentials,
            final long amountShillings,
            final String phone2547,
            final String accountReference,
            final String description,
            final String callbackUrl) {
        final String timestamp = timestamp();
        final Map<String, Object> request = new LinkedHashMap<>();
        request.put("BusinessShortCode", credentials.shortcode());
        request.put("Password", password(credentials, timestamp));
        request.put("Timestamp", timestamp);
        request.put("TransactionType", credentials.till() ? "CustomerBuyGoodsOnline" : "CustomerPayBillOnline");
        request.put("Amount", amountShillings);
        request.put("PartyA", phone2547);
        request.put("PartyB", credentials.shortcode());
        request.put("PhoneNumber", phone2547);
        request.put("CallBackURL", callbackUrl);
        request.put("AccountReference", accountReference);
        request.put("TransactionDesc", description);
        final JsonNode body = post(credentials, "/mpesa/stkpush/v1/processrequest", request);
        return new StkPushResult(
                text(body, "MerchantRequestID"), text(body, "CheckoutRequestID"), text(body, "ResponseCode"), text(body, "CustomerMessage"));
    }

    public StkQueryResult stkQuery(final Credentials credentials, final String checkoutRequestId) {
        final String timestamp = timestamp();
        final Map<String, Object> request = new LinkedHashMap<>();
        request.put("BusinessShortCode", credentials.shortcode());
        request.put("Password", password(credentials, timestamp));
        request.put("Timestamp", timestamp);
        request.put("CheckoutRequestID", checkoutRequestId);
        try {
            final JsonNode body = post(credentials, "/mpesa/stkpushquery/v1/query", request);
            return new StkQueryResult(text(body, "ResultCode"), text(body, "ResultDesc"), null);
        } catch (DarajaException e) {
            if ("500.001.1001".equals(e.errorCode())) {
                return new StkQueryResult(null, null, e.errorCode());
            }
            throw e;
        }
    }

    /** The answer arrives later on {@code resultUrl}. The security credential is the tenant's, as Daraja's portal generated it. */
    public Accepted transactionStatus(
            final Credentials credentials, final String transactionId, final String resultUrl, final String timeoutUrl) {
        final Map<String, Object> request = new LinkedHashMap<>();
        request.put("Initiator", credentials.initiatorName());
        request.put("SecurityCredential", credentials.securityCredential());
        request.put("CommandID", "TransactionStatusQuery");
        request.put("TransactionID", transactionId);
        request.put("PartyA", credentials.shortcode());
        request.put("IdentifierType", "4");
        request.put("ResultURL", resultUrl);
        request.put("QueueTimeOutURL", timeoutUrl);
        request.put("Remarks", "Reconciliation");
        request.put("Occasion", "Reconciliation");
        final JsonNode body = post(credentials, "/mpesa/transactionstatus/v1/query", request);
        return new Accepted(text(body, "ConversationID"), text(body, "OriginatorConversationID"),
                text(body, "ResponseCode"), text(body, "ResponseDescription"));
    }

    String timestamp() {
        return TIMESTAMP.format(clock.instant().atZone(EAT));
    }

    static String password(final Credentials credentials, final String timestamp) {
        return Base64.getEncoder().encodeToString(
                (credentials.shortcode() + credentials.passkey() + timestamp).getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode post(final Credentials credentials, final String path, final Map<String, Object> request) {
        return send(HttpRequest.newBuilder(uri(credentials, path))
                .header("Authorization", "Bearer " + accessToken(credentials))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(request), StandardCharsets.UTF_8)));
    }

    private JsonNode send(final HttpRequest.Builder request) {
        final Transport selected = borrowTransport();
        try {
            final HttpResponse<String> response;
            try {
                response = selected.http.send(request.timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofString());
            } catch (java.io.IOException e) {
                throw new DarajaException("unreachable", null, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DarajaException("interrupted", null, e);
            }
            JsonNode body;
            try {
                body = json.readTree(response.body());
            } catch (RuntimeException e) {
                body = null;
            }
            if (response.statusCode() / 100 != 2) {
                final String errorCode = body == null ? null : body.path("errorCode").asString(null);
                if (response.statusCode() / 100 == 5 && "500.003.1001".equals(errorCode)) {
                    retireTransport(selected);
                }
                // An ambiguous provider failure must never replay a money-moving POST here.
                throw new DarajaException("HTTP " + response.statusCode(), errorCode, null);
            }
            if (body == null) {
                throw new DarajaException("unreadable response", null, null);
            }
            return body;
        } finally {
            releaseTransport(selected);
        }
    }

    private Transport borrowTransport() {
        synchronized (transportLock) {
            transport.inFlight++;
            return transport;
        }
    }

    /** ADR 0022: discard a failing pool for the next call, retaining the valid OAuth token. */
    private void retireTransport(final Transport failed) {
        synchronized (transportLock) {
            if (transport == failed) {
                transport = new Transport();
                failed.retired = true;
            }
        }
    }

    private void releaseTransport(final Transport selected) {
        synchronized (transportLock) {
            selected.inFlight--;
            if (selected.retired && selected.inFlight == 0) {
                selected.http.shutdown();
            }
        }
    }

    /** Counters are guarded by transportLock; retirement never interrupts an in-flight call. */
    private static final class Transport {
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        private int inFlight;
        private boolean retired;
    }

    private URI uri(final Credentials credentials, final String path) {
        return URI.create((credentials.sandbox() ? sandboxUrl : productionUrl) + path);
    }

    private static String text(final JsonNode body, final String field) {
        final JsonNode value = body.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private record CachedToken(String token, Instant expiresAt) {}

    /** A failed Daraja call; the message never contains credentials or payloads. */
    public static class DarajaException extends RuntimeException {

        private final String errorCode;

        DarajaException(final String message, final String errorCode, final Throwable cause) {
            super("Daraja: " + message + (errorCode == null ? "" : " (" + errorCode + ")"), cause);
            this.errorCode = errorCode;
        }

        public String errorCode() {
            return errorCode;
        }
    }
}
