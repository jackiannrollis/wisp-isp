package com.corebalt.isp.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Admits M-Pesa callbacks only from allowed networks (docs/05 Callback handling 1); SMS delivery
 * reports rely on their path token, as Africa's Talking publishes no fixed addresses. The address
 * is the request's remote address; behind Caddy, Tomcat's RemoteIpValve
 * ({@code server.forward-headers-strategy: native}) sets it from X-Forwarded-For, trusting only
 * internal proxies, so the backend port must never be reachable except through the proxy.
 */
public class CallbackSourceFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CallbackSourceFilter.class);

    private final List<Cidr> allowed;

    public CallbackSourceFilter(final List<String> cidrs) {
        this.allowed = cidrs.stream().map(String::strip).filter(c -> !c.isEmpty()).map(Cidr::parse).toList();
    }

    /** Only provider callbacks with published source addresses (M-Pesa) are checked. */
    @Override
    protected boolean shouldNotFilter(final HttpServletRequest request) {
        return !request.getRequestURI().startsWith(request.getContextPath() + "/callbacks/mpesa/");
    }

    @Override
    protected void doFilterInternal(
            final HttpServletRequest request, final HttpServletResponse response, final FilterChain chain)
            throws ServletException, IOException {
        if (!isAllowed(request.getRemoteAddr())) {
            log.warn("Callback refused from {}", request.getRemoteAddr());
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        chain.doFilter(request, response);
    }

    boolean isAllowed(final String address) {
        try {
            final InetAddress inet = InetAddress.ofLiteral(address);
            return allowed.stream().anyMatch(cidr -> cidr.contains(inet));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    record Cidr(BigInteger network, int prefix, int bits) {

        static Cidr parse(final String text) {
            final String[] parts = text.split("/");
            try {
                final InetAddress address = InetAddress.getByName(parts[0]);
                final int bits = address.getAddress().length * 8;
                final int prefix = parts.length == 2 ? Integer.parseInt(parts[1]) : bits;
                return new Cidr(masked(new BigInteger(1, address.getAddress()), prefix, bits), prefix, bits);
            } catch (UnknownHostException | NumberFormatException e) {
                throw new IllegalArgumentException("not a CIDR: " + text, e);
            }
        }

        boolean contains(final InetAddress address) {
            return address.getAddress().length * 8 == bits
                    && masked(new BigInteger(1, address.getAddress()), prefix, bits).equals(network);
        }

        private static BigInteger masked(final BigInteger value, final int prefix, final int bits) {
            return value.shiftRight(bits - prefix).shiftLeft(bits - prefix);
        }
    }
}
