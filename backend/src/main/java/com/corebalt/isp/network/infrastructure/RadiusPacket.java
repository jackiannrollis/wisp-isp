package com.corebalt.isp.network.infrastructure;

import java.io.ByteArrayOutputStream;
import java.net.Inet4Address;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The few RADIUS packets the tunnel worker sends and reads: Disconnect and CoA (RFC 5176) with their
 * ACK and NAK. Requests carry the RFC 2866-style authenticator, MD5(code, identifier, length, sixteen
 * zero octets, attributes, secret); a reply is accepted only when its authenticator, MD5 over the
 * reply with the request's authenticator in place plus the secret, proves it knew the secret. The
 * login probe (ADR 0019) also sends an Access-Request (RFC 2865): a random authenticator, the password
 * hidden as section 5.2 says, and a Message-Authenticator (RFC 3579), which FreeRADIUS may require.
 */
public final class RadiusPacket {

    public static final int ACCESS_REQUEST = 1;
    public static final int ACCESS_ACCEPT = 2;
    public static final int ACCESS_REJECT = 3;
    public static final int DISCONNECT_REQUEST = 40;
    public static final int DISCONNECT_ACK = 41;
    public static final int DISCONNECT_NAK = 42;
    public static final int COA_REQUEST = 43;
    public static final int COA_ACK = 44;
    public static final int COA_NAK = 45;

    public static final int USER_NAME = 1;
    public static final int USER_PASSWORD = 2;
    public static final int NAS_IP_ADDRESS = 4;
    public static final int SESSION_TIMEOUT = 27;
    public static final int MESSAGE_AUTHENTICATOR = 80;
    public static final int FRAMED_POOL = 88;
    public static final int VENDOR_SPECIFIC = 26;
    public static final int ACCT_SESSION_ID = 44;
    public static final int ERROR_CAUSE = 101;
    /** RFC 5176 Error-Cause: the NAS has no such session (it ended or the client redialled). */
    public static final int SESSION_CONTEXT_NOT_FOUND = 503;

    public static final int MIKROTIK_VENDOR = 14988;
    public static final int MIKROTIK_RATE_LIMIT = 8;
    public static final int MIKROTIK_ADDRESS_LIST = 19;

    private static final int HEADER = 20;
    private static final int MAX_LENGTH = 4096;

    /** One attribute; a vendor-specific one keeps its vendor ID and vendor type. */
    public record Attribute(int vendor, int type, byte[] value) {

        public static Attribute string(final int type, final String value) {
            return new Attribute(0, type, value.getBytes(StandardCharsets.UTF_8));
        }

        public static Attribute vendorString(final int vendor, final int type, final String value) {
            return new Attribute(vendor, type, value.getBytes(StandardCharsets.UTF_8));
        }

        public static Attribute integer(final int type, final int value) {
            return new Attribute(0, type, ByteBuffer.allocate(4).putInt(value).array());
        }

        public static Attribute address(final int type, final Inet4Address value) {
            return new Attribute(0, type, value.getAddress());
        }
    }

    private final int code;
    private final int identifier;
    private final byte[] authenticator;
    private final List<Attribute> attributes;
    private final byte[] raw;

    private RadiusPacket(final int code, final int identifier, final byte[] authenticator, final List<Attribute> attributes,
                         final byte[] raw) {
        this.code = code;
        this.identifier = identifier;
        this.authenticator = authenticator;
        this.attributes = attributes;
        this.raw = raw;
    }

    /** A Disconnect- or CoA-Request, signed with the router's secret. */
    public static byte[] request(final int code, final int identifier, final List<Attribute> attributes, final byte[] secret) {
        final byte[] packet = assemble(code, identifier, new byte[16], attributes);
        System.arraycopy(md5(packet, secret), 0, packet, 4, 16);
        return packet;
    }

    /**
     * An Access-Request for {@code username} and {@code password} with the random
     * {@code requestAuthenticator}: the password hidden with the secret (RFC 2865, 5.2), {@code extra}
     * attributes (NAS-IP-Address), and a Message-Authenticator last, HMAC-MD5 of the packet (RFC 3579).
     */
    public static byte[] accessRequest(final int identifier, final byte[] requestAuthenticator, final String username,
                                       final String password, final List<Attribute> extra, final byte[] secret) {
        final List<Attribute> attributes = new ArrayList<>();
        attributes.add(Attribute.string(USER_NAME, username));
        attributes.add(new Attribute(0, USER_PASSWORD,
                hidePassword(password.getBytes(StandardCharsets.UTF_8), secret, requestAuthenticator)));
        attributes.addAll(extra);
        attributes.add(new Attribute(0, MESSAGE_AUTHENTICATOR, new byte[16]));
        final byte[] packet = assemble(ACCESS_REQUEST, identifier, requestAuthenticator, attributes);
        System.arraycopy(hmacMd5(packet, secret), 0, packet, packet.length - 16, 16);
        return packet;
    }

    /** RFC 2865, 5.2: the password padded to 16 octets, each block XORed with an MD5 chain over the secret. */
    static byte[] hidePassword(final byte[] password, final byte[] secret, final byte[] requestAuthenticator) {
        if (password.length > 128) {
            throw new IllegalArgumentException("RADIUS passwords are at most 128 octets");
        }
        final byte[] padded = Arrays.copyOf(password, Math.max(16, (password.length + 15) / 16 * 16));
        byte[] previous = requestAuthenticator;
        for (int block = 0; block < padded.length; block += 16) {
            final byte[] key = md5(secret, previous);
            for (int i = 0; i < 16; i++) {
                padded[block + i] ^= key[i];
            }
            previous = Arrays.copyOfRange(padded, block, block + 16);
        }
        return padded;
    }

    /** An ACK or NAK to a request whose authenticator was {@code requestAuthenticator}. */
    public static byte[] response(final int code, final int identifier, final byte[] requestAuthenticator,
                                  final List<Attribute> attributes, final byte[] secret) {
        final byte[] packet = assemble(code, identifier, requestAuthenticator, attributes);
        System.arraycopy(md5(packet, secret), 0, packet, 4, 16);
        return packet;
    }

    /** Parses a datagram; throws IllegalArgumentException when it is not a well-formed RADIUS packet. */
    public static RadiusPacket parse(final byte[] data, final int length) {
        if (length < HEADER || length > MAX_LENGTH) {
            throw new IllegalArgumentException("not a RADIUS packet: " + length + " octets");
        }
        final int declared = ((data[2] & 0xff) << 8) | (data[3] & 0xff);
        if (declared < HEADER || declared > length) {
            throw new IllegalArgumentException("RADIUS length " + declared + " does not fit " + length + " octets");
        }
        final byte[] raw = Arrays.copyOf(data, declared);
        final List<Attribute> attributes = new ArrayList<>();
        int at = HEADER;
        while (at < declared) {
            if (at + 2 > declared) {
                throw new IllegalArgumentException("truncated attribute");
            }
            final int type = raw[at] & 0xff;
            final int len = raw[at + 1] & 0xff;
            if (len < 2 || at + len > declared) {
                throw new IllegalArgumentException("bad attribute length " + len);
            }
            if (type == VENDOR_SPECIFIC && len >= 8) {
                final int vendor = ByteBuffer.wrap(raw, at + 2, 4).getInt();
                int sub = at + 6;
                while (sub + 2 <= at + len) {
                    final int subLen = raw[sub + 1] & 0xff;
                    if (subLen < 2 || sub + subLen > at + len) {
                        throw new IllegalArgumentException("bad vendor attribute length " + subLen);
                    }
                    attributes.add(new Attribute(vendor, raw[sub] & 0xff, Arrays.copyOfRange(raw, sub + 2, sub + subLen)));
                    sub += subLen;
                }
            } else {
                attributes.add(new Attribute(0, type, Arrays.copyOfRange(raw, at + 2, at + len)));
            }
            at += len;
        }
        return new RadiusPacket(raw[0] & 0xff, raw[1] & 0xff, Arrays.copyOfRange(raw, 4, 20), List.copyOf(attributes), raw);
    }

    /** For a request: was it signed with {@code secret}? */
    public boolean requestSignedWith(final byte[] secret) {
        final byte[] copy = raw.clone();
        Arrays.fill(copy, 4, 20, (byte) 0);
        return MessageDigest.isEqual(md5(copy, secret), authenticator);
    }

    /** For a reply: does it answer the request with {@code requestAuthenticator} and know {@code secret}? */
    public boolean answers(final byte[] requestAuthenticator, final byte[] secret) {
        final byte[] copy = raw.clone();
        System.arraycopy(requestAuthenticator, 0, copy, 4, 16);
        return MessageDigest.isEqual(md5(copy, secret), authenticator);
    }

    public int code() {
        return code;
    }

    public int identifier() {
        return identifier;
    }

    public byte[] authenticator() {
        return authenticator.clone();
    }

    public List<Attribute> attributes() {
        return attributes;
    }

    public Optional<String> string(final int type) {
        return vendorString(0, type);
    }

    public Optional<String> vendorString(final int vendor, final int type) {
        return attributes.stream()
                .filter(a -> a.vendor() == vendor && a.type() == type)
                .findFirst()
                .map(a -> new String(a.value(), StandardCharsets.UTF_8));
    }

    public OptionalInt integer(final int type) {
        return attributes.stream()
                .filter(a -> a.vendor() == 0 && a.type() == type && a.value().length == 4)
                .mapToInt(a -> ByteBuffer.wrap(a.value()).getInt())
                .findFirst();
    }

    private static byte[] assemble(final int code, final int identifier, final byte[] authenticator,
                                   final List<Attribute> attributes) {
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (final Attribute attribute : attributes) {
            final byte[] value = attribute.value();
            if (attribute.vendor() == 0) {
                if (value.length > 253) {
                    throw new IllegalArgumentException("attribute " + attribute.type() + " too long");
                }
                body.write(attribute.type());
                body.write(value.length + 2);
            } else {
                if (value.length > 247) {
                    throw new IllegalArgumentException("vendor attribute " + attribute.type() + " too long");
                }
                body.write(VENDOR_SPECIFIC);
                body.write(value.length + 8);
                body.writeBytes(ByteBuffer.allocate(4).putInt(attribute.vendor()).array());
                body.write(attribute.type());
                body.write(value.length + 2);
            }
            body.writeBytes(value);
        }
        final int length = HEADER + body.size();
        if (length > MAX_LENGTH) {
            throw new IllegalArgumentException("RADIUS packet too long: " + length);
        }
        final ByteBuffer packet = ByteBuffer.allocate(length);
        packet.put((byte) code).put((byte) identifier).putShort((short) length).put(authenticator).put(body.toByteArray());
        return packet.array();
    }

    private static byte[] hmacMd5(final byte[] packet, final byte[] secret) {
        try {
            final Mac mac = Mac.getInstance("HmacMD5");
            mac.init(new SecretKeySpec(secret, "HmacMD5"));
            return mac.doFinal(packet);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] md5(final byte[] packet, final byte[] secret) {
        try {
            final MessageDigest md5 = MessageDigest.getInstance("MD5");
            md5.update(packet);
            md5.update(secret);
            return md5.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
