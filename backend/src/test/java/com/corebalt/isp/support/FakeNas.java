package com.corebalt.isp.support;

import com.corebalt.isp.network.infrastructure.RadiusPacket;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Stand-in for a router's RADIUS dynamic-authorization port (RFC 5176, UDP 3799), on one address. It
 * keeps every request it receives, answers only requests signed with its secret (like a real NAS, it
 * drops the rest) and replies as {@link #mode} says.
 */
public final class FakeNas implements AutoCloseable {

    public enum Mode {
        ACK,
        /** NAK with Error-Cause 503: the session already ended. */
        SESSION_GONE,
        /** NAK with Error-Cause 404 (Invalid Request). */
        REFUSE,
        /** Never answers. */
        SILENT,
        /** Answers ACK signed with the wrong secret. */
        FORGED
    }

    /** A request as received, and whether it was signed with the secret in force at the time. */
    public record Received(RadiusPacket packet, boolean signedWithSecret) {}

    private final DatagramSocket socket;
    private final Thread thread;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private volatile byte[] secret;
    private volatile Mode mode = Mode.ACK;

    public FakeNas(final String address, final int port, final String secret) {
        try {
            socket = new DatagramSocket(new InetSocketAddress(address, port));
        } catch (SocketException e) {
            throw new UncheckedIOException(e);
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        thread = Thread.ofPlatform().daemon().name("fake-nas-" + address).start(this::serve);
    }

    public int port() {
        return socket.getLocalPort();
    }

    public FakeNas mode(final Mode mode) {
        this.mode = mode;
        return this;
    }

    public FakeNas secret(final String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        return this;
    }

    public List<Received> received() {
        return List.copyOf(received);
    }

    private void serve() {
        final byte[] buffer = new byte[4096];
        while (!socket.isClosed()) {
            final DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(datagram);
            } catch (IOException e) {
                return;
            }
            final RadiusPacket request;
            try {
                request = RadiusPacket.parse(datagram.getData(), datagram.getLength());
            } catch (IllegalArgumentException e) {
                continue;
            }
            final byte[] current = secret;
            final boolean signed = request.requestSignedWith(current);
            received.add(new Received(request, signed));
            if (!signed || mode == Mode.SILENT) {
                continue;
            }
            final int ack = request.code() + 1;
            final int nak = request.code() + 2;
            final byte[] reply = switch (mode) {
                case ACK -> RadiusPacket.response(ack, request.identifier(), request.authenticator(), List.of(), current);
                case SESSION_GONE -> RadiusPacket.response(nak, request.identifier(), request.authenticator(),
                        List.of(RadiusPacket.Attribute.integer(RadiusPacket.ERROR_CAUSE, RadiusPacket.SESSION_CONTEXT_NOT_FOUND)),
                        current);
                case REFUSE -> RadiusPacket.response(nak, request.identifier(), request.authenticator(),
                        List.of(RadiusPacket.Attribute.integer(RadiusPacket.ERROR_CAUSE, 404)), current);
                case FORGED -> RadiusPacket.response(ack, request.identifier(), request.authenticator(), List.of(),
                        "not-the-router-secret".getBytes(StandardCharsets.UTF_8));
                case SILENT -> throw new IllegalStateException();
            };
            try {
                socket.send(new DatagramPacket(reply, reply.length, datagram.getSocketAddress()));
            } catch (IOException e) {
                return;
            }
        }
    }

    @Override
    public void close() {
        socket.close();
        try {
            thread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
