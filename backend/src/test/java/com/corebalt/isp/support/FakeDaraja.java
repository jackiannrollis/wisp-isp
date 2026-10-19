package com.corebalt.isp.support;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A stand-in for Safaricom Daraja on a random local port: records every request and answers with
 * canned, production-shaped responses. Override an answer per path with {@link #respond}.
 */
public final class FakeDaraja implements AutoCloseable {

    public record Recorded(String method, String path, Map<String, List<String>> headers, String body) {

        public String header(final String name) {
            return headers.entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> e.getValue().getFirst())
                    .findFirst()
                    .orElse(null);
        }
    }

    private record Answer(int status, String body) {}

    private final HttpServer server;
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private final Map<String, Answer> answers = new ConcurrentHashMap<>();
    private final Map<String, Runnable> beforeAnswer = new ConcurrentHashMap<>();

    public FakeDaraja() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        respond("/oauth/v1/generate", 200, "{\"access_token\":\"fake-access-token\",\"expires_in\":\"3599\"}");
        respond("/mpesa/c2b/v2/registerurl", 200,
                "{\"OriginatorCoversationID\":\"6e86-45dd-91ac-fd5d4178ab523408729\",\"ResponseCode\":\"0\",\"ResponseDescription\":\"Success\"}");
        respond("/mpesa/stkpush/v1/processrequest", 200, """
                {"MerchantRequestID":"29115-34620561-1","CheckoutRequestID":"ws_CO_191220191020363925",
                 "ResponseCode":"0","ResponseDescription":"Success. Request accepted for processing",
                 "CustomerMessage":"Success. Request accepted for processing"}""");
        respond("/mpesa/stkpushquery/v1/query", 500,
                "{\"requestId\":\"1-2\",\"errorCode\":\"500.001.1001\",\"errorMessage\":\"The transaction is being processed\"}");
        respond("/mpesa/transactionstatus/v1/query", 200, """
                {"OriginatorConversationID":"1236-7134259-1","ConversationID":"AG_20240126_00004a4b8c4d",
                 "ResponseCode":"0","ResponseDescription":"Accept the service request successfully."}""");
        server.createContext("/", exchange -> {
            final String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            final String path = exchange.getRequestURI().getPath();
            requests.add(new Recorded(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                    Map.copyOf(exchange.getRequestHeaders()), body));
            final Runnable hook = beforeAnswer.remove(path);
            if (hook != null) {
                hook.run();
            }
            final Answer answer = answers.getOrDefault(path, new Answer(404, "{\"errorCode\":\"404.001.01\"}"));
            final byte[] bytes = answer.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(answer.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void respond(final String path, final int status, final String body) {
        answers.put(path, new Answer(status, body));
    }

    /** Runs {@code hook} once, the next time {@code path} is called, before answering (races). */
    public void beforeNextAnswer(final String path, final Runnable hook) {
        beforeAnswer.put(path, hook);
    }

    public List<Recorded> requests() {
        return List.copyOf(requests);
    }

    public List<Recorded> requestsTo(final String pathPrefix) {
        return requests.stream().filter(r -> r.path().startsWith(pathPrefix)).toList();
    }

    public void clear() {
        requests.clear();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
