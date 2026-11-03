package com.corebalt.isp.support;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import tools.jackson.databind.json.JsonMapper;

/**
 * Celcom's documented JSON send contract, including its misspelled respose-code and numeric IDs.
 * No real message is sent. Per-number errors exercise the application's retry and tenant alerts.
 */
public final class FakeCelcom implements AutoCloseable {

    public static final String API_KEY = "fake-celcom-api-key";
    public static final String PARTNER_ID = "fake-partner-id";

    public record Recorded(Map<String, String> headers, Map<String, String> form) {}

    private record Answer(int httpStatus, int statusCode, String status) {}

    private final HttpServer server;
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private final Map<String, Answer> byRecipient = new ConcurrentHashMap<>();
    private final Map<String, String> rawResponses = new ConcurrentHashMap<>();
    private final Map<String, String> deliveryResponses = new ConcurrentHashMap<>();
    private final Set<String> registeredSenders = ConcurrentHashMap.newKeySet();
    private final AtomicLong messageIds = new AtomicLong(8_000_000);
    private final JsonMapper json = JsonMapper.builder().build();

    public FakeCelcom() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/api/services/sendsms/", exchange -> {
            final Map<String, String> form = json.readValue(exchange.getRequestBody(),
                    new tools.jackson.core.type.TypeReference<Map<String, String>>() {});
            final Map<String, String> headers = new HashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), v.getFirst()));
            requests.add(new Recorded(headers, form));

            final String to = form.getOrDefault("mobile", "");
            final Answer configured = byRecipient.get("+" + to);
            int http = 200;
            String body;
            if (!API_KEY.equals(form.get("apikey")) || !PARTNER_ID.equals(form.get("partnerID"))) {
                body = "{\"responses\":[{\"respose-code\":1006,\"response-description\":\"Invalid credentials\"}]}";
            } else if (rawResponses.containsKey("+" + to)) {
                body = rawResponses.get("+" + to);
            } else if (configured != null && configured.httpStatus() != 200) {
                http = configured.httpStatus();
                body = "{\"error\":\"server error\"}";
            } else {
                final Answer answer = !registeredSenders.contains(form.getOrDefault("shortcode", ""))
                        ? new Answer(200, 1001, "Invalid sender ID")
                        : configured != null ? configured : new Answer(200, 200, "Success");
                final boolean sent = answer.statusCode() == 200;
                body = """
                        {"responses":[{"respose-code":%d,"response-description":"%s",
                         "mobile":"%s","messageid":%s,"networkid":"1"}]}
                        """.formatted(answer.statusCode(), answer.status(), to,
                        sent ? Long.toString(messageIds.incrementAndGet()) : "null");
            }
            final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(http, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/api/services/getdlr/", exchange -> {
            final var payload = json.readTree(exchange.getRequestBody());
            final String id = payload.path("messageID").asString();
            final String body = deliveryResponses.getOrDefault(id,
                    "{\"response-code\":200,\"message-id\":\"" + id
                            + "\",\"delivery-status\":32,\"delivery-description\":\"DeliveredToTerminal\"}");
            final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void registerSender(final String senderId) {
        registeredSenders.add(senderId);
    }

    /** Per-recipient Celcom status code, e.g. 1003 invalid number, 1004 low credits. */
    public void answerFor(final String recipient, final int statusCode, final String status) {
        byRecipient.put(recipient, new Answer(200, statusCode, status));
    }

    /** An HTTP failure for this recipient (500, 502, ...). */
    public void failFor(final String recipient, final int httpStatus) {
        byRecipient.put(recipient, new Answer(httpStatus, 0, ""));
    }

    public void reset(final String recipient) {
        byRecipient.remove(recipient);
        rawResponses.remove(recipient);
    }

    public void respondWith(final String recipient, final String body) {
        rawResponses.put(recipient, body);
    }

    public void deliveryResponse(final String id, final String body) { deliveryResponses.put(id, body); }

    public List<Recorded> requestsTo(final String recipient) {
        return requests.stream().filter(r -> recipient.replaceFirst("^\\+", "").equals(r.form().get("mobile"))).toList();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
