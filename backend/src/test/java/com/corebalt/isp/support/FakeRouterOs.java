package com.corebalt.isp.support;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Stand-in RouterOS v7 REST API: {@code GET /rest/system/resource} with basic auth, answering like
 * a CHR (every value a string). Routers are told apart by the address they were called on (their
 * tunnel IP, any 127.x address), so one server plays many routers. Behaviour per address: UP (the
 * default), DOWN (503, as a stand-in for no answer) or the login it expects.
 */
public final class FakeRouterOs implements AutoCloseable {

    public static final String USERNAME = "corebalt-monitor";
    /** Obvious fake, used only by tests. */
    public static final String PASSWORD = "fake-routeros-rest-password";

    private final HttpServer server;
    private final Map<String, Boolean> down = new ConcurrentHashMap<>();
    private final Map<String, List<String>> sessions = new ConcurrentHashMap<>();

    public FakeRouterOs() {
        try {
            // Every loopback address reaches it, so each test router has its own 127.x tunnel IP.
            server = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/rest/system/resource", exchange -> {
            final String address = exchange.getLocalAddress().getAddress().getHostAddress();
            final String expected = "Basic " + Base64.getEncoder().encodeToString(
                    (USERNAME + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
            final int status;
            final String body;
            if (down.getOrDefault(address, false)) {
                status = 503;
                body = "";
            } else if (!expected.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                status = 401;
                body = "{\"error\":401,\"message\":\"Unauthorized\"}";
            } else {
                status = 200;
                body = """
                        {"architecture-name":"x86_64","board-name":"CHR","cpu":"QEMU","cpu-count":"1","cpu-load":"3",
                         "free-hdd-space":"91000000","free-memory":"180000000","total-memory":"268435456",
                         "uptime":"1w2d3h4m5s","version":"7.16.1 (stable)"}""";
            }
            final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.createContext("/rest/ppp/active", exchange -> {
            final String address = exchange.getLocalAddress().getAddress().getHostAddress();
            final String expected = "Basic " + Base64.getEncoder().encodeToString(
                    (USERNAME + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
            final int status = down.getOrDefault(address, false) ? 503
                    : expected.equals(exchange.getRequestHeaders().getFirst("Authorization")) ? 200 : 401;
            final String body = status != 200 ? "" : sessions.getOrDefault(address, List.of()).stream()
                    .map(name -> "{\".id\":\"*1\",\"name\":\"" + name + "\",\"service\":\"pppoe\",\"uptime\":\"5m\"}")
                    .collect(Collectors.joining(",", "[", "]"));
            final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
    }

    /** The PPPoE sessions the router at {@code tunnelIp} reports in /ppp/active. */
    public void sessions(final String tunnelIp, final List<String> usernames) {
        sessions.put(tunnelIp, List.copyOf(usernames));
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void down(final String tunnelIp) {
        down.put(tunnelIp, true);
    }

    public void up(final String tunnelIp) {
        down.remove(tunnelIp);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
