package com.corebalt.isp.support;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A stand-in for Africa's Talking SMS ({@code POST /version1/messaging}) with production-shaped
 * answers. It refuses a missing or unregistered sender ID (402 InvalidSenderId) and a wrong API key
 * (HTTP 401), and answers per recipient number as configured with {@link #answerFor}.
 */
public final class FakeAfricasTalking implements AutoCloseable {

    public static final String API_KEY = "fake-at-api-key";

    public record Recorded(Map<String, String> headers, Map<String, String> form) {}

    private record Answer(int httpStatus, int statusCode, String status) {}

    private final HttpServer server;
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private final Map<String, Answer> byRecipient = new ConcurrentHashMap<>();
    private final Set<String> registeredSenders = ConcurrentHashMap.newKeySet();

    public FakeAfricasTalking() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/version1/messaging", exchange -> {
            final Map<String, String> form = new HashMap<>();
            for (final String pair : new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).split("&")) {
                final int eq = pair.indexOf('=');
                if (eq > 0) {
                    form.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                            URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
                }
            }
            final Map<String, String> headers = new HashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), v.getFirst()));
            requests.add(new Recorded(headers, form));

            final String to = form.getOrDefault("to", "");
            final Answer configured = byRecipient.get(to);
            int http = 201;
            String body;
            if (!API_KEY.equals(headers.get("apikey"))) {
                http = 401;
                body = "The supplied authentication is invalid";
            } else if (configured != null && configured.httpStatus() != 201) {
                http = configured.httpStatus();
                body = "{\"error\":\"server error\"}";
            } else {
                final Answer answer = !registeredSenders.contains(form.getOrDefault("from", ""))
                        ? new Answer(201, 402, "InvalidSenderId")
                        : configured != null ? configured : new Answer(201, 101, "Success");
                final boolean sent = answer.statusCode() >= 100 && answer.statusCode() <= 102;
                body = """
                        {"SMSMessageData":{"Message":"Sent to %d/1 Total Cost: KES %s",
                         "Recipients":[{"statusCode":%d,"number":"%s","status":"%s","cost":"%s","messageId":"%s"}]}}
                        """.formatted(sent ? 1 : 0, sent ? "0.8000" : "0", answer.statusCode(), to, answer.status(),
                        sent ? "KES 0.8000" : "0", sent ? "ATXid_" + UUID.randomUUID() : "None");
            }
            final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(http, bytes.length);
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

    /** Per-recipient status code, e.g. 403 InvalidPhoneNumber, 405 InsufficientBalance. */
    public void answerFor(final String recipient, final int statusCode, final String status) {
        byRecipient.put(recipient, new Answer(201, statusCode, status));
    }

    /** An HTTP failure for this recipient (500, 502, ...). */
    public void failFor(final String recipient, final int httpStatus) {
        byRecipient.put(recipient, new Answer(httpStatus, 0, ""));
    }

    public void reset(final String recipient) {
        byRecipient.remove(recipient);
    }

    public List<Recorded> requestsTo(final String recipient) {
        return requests.stream().filter(r -> recipient.equals(r.form().get("to"))).toList();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
