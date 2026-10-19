package com.corebalt.isp.network.infrastructure;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Sends one Disconnect- or CoA-Request to a router over the WireGuard tunnel (N5, N6) and waits for
 * its answer. The socket is connected to the router, so only datagrams from its address and port are
 * read, and a reply counts only when its identifier matches and its authenticator proves the router
 * knows the secret. Retries are the caller's ({@code SessionReauthorizer}); the secret is used for
 * this one exchange and never logged.
 */
@Component
public class RadiusCoaClient {

    /** What the router answered: ACK, or NAK with its Error-Cause (null when it gave none). */
    public record Answer(boolean ack, Integer errorCause) {}

    /** No valid answer within the timeout, or the packet could not be sent. */
    public static class NoAnswer extends Exception {
        public NoAnswer(final String message) {
            super(message);
        }
    }

    private final SecureRandom random = new SecureRandom();
    private final int port;
    private final Duration timeout;

    public RadiusCoaClient(@Value("${isp.worker.coa.port:3799}") final int port,
                           @Value("${isp.worker.coa.timeout:2s}") final Duration timeout) {
        this.port = port;
        this.timeout = timeout;
    }

    public Answer send(final String routerAddress, final byte[] secret, final int code,
                       final List<RadiusPacket.Attribute> attributes) throws NoAnswer {
        final int identifier = random.nextInt(256);
        final byte[] request = RadiusPacket.request(code, identifier, attributes, secret);
        final byte[] requestAuthenticator = Arrays.copyOfRange(request, 4, 20);
        final long deadline = System.nanoTime() + timeout.toNanos();
        try (DatagramSocket socket = new DatagramSocket()) {
            final InetSocketAddress router = new InetSocketAddress(InetAddress.getByName(routerAddress), port);
            socket.connect(router);
            socket.send(new DatagramPacket(request, request.length));
            final byte[] buffer = new byte[4096];
            while (true) {
                final long left = Duration.ofNanos(deadline - System.nanoTime()).toMillis();
                if (left <= 0) {
                    throw new NoAnswer("no answer from " + routerAddress + " within " + timeout.toMillis() + " ms");
                }
                socket.setSoTimeout((int) left);
                final DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(datagram);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                final RadiusPacket reply;
                try {
                    reply = RadiusPacket.parse(datagram.getData(), datagram.getLength());
                } catch (IllegalArgumentException e) {
                    continue;
                }
                if (reply.identifier() != identifier || !reply.answers(requestAuthenticator, secret)) {
                    continue;
                }
                if (reply.code() == code + 1) {
                    return new Answer(true, null);
                }
                if (reply.code() == code + 2) {
                    final OptionalInt cause = reply.integer(RadiusPacket.ERROR_CAUSE);
                    return new Answer(false, cause.isPresent() ? cause.getAsInt() : null);
                }
            }
        } catch (IOException e) {
            // ICMP port unreachable surfaces as PortUnreachableException on a connected socket.
            throw new NoAnswer("unreachable: " + e.getClass().getSimpleName());
        }
    }
}
