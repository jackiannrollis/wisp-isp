package com.corebalt.isp.network.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

/** RFC 5176 packets: layout, the request authenticator and reply verification (N5). */
class RadiusPacketTest {

    private static final byte[] SECRET = "fake-router-secret".getBytes(StandardCharsets.UTF_8);

    @Test
    void N5_coaRequestLayoutAndMikrotikRateLimitVendorAttribute() {
        final byte[] packet = RadiusPacket.request(RadiusPacket.COA_REQUEST, 7, List.of(
                RadiusPacket.Attribute.string(RadiusPacket.USER_NAME, "CB000001"),
                RadiusPacket.Attribute.vendorString(RadiusPacket.MIKROTIK_VENDOR, RadiusPacket.MIKROTIK_RATE_LIMIT, "5M/10M")),
                SECRET);

        assertThat(packet[0]).isEqualTo((byte) 43);
        assertThat(packet[1]).isEqualTo((byte) 7);
        assertThat(((packet[2] & 0xff) << 8) | (packet[3] & 0xff)).isEqualTo(packet.length).isEqualTo(20 + 10 + 14);
        // Vendor-Specific(26), length 14, vendor 14988 (0x3a8c), Mikrotik-Rate-Limit(8), length 8, "5M/10M".
        assertThat(HexFormat.of().formatHex(packet, 30, 44)).isEqualTo("1a0e00003a8c0808" + "354d2f31304d");

        final RadiusPacket parsed = RadiusPacket.parse(packet, packet.length);
        assertThat(parsed.string(RadiusPacket.USER_NAME)).contains("CB000001");
        assertThat(parsed.vendorString(RadiusPacket.MIKROTIK_VENDOR, RadiusPacket.MIKROTIK_RATE_LIMIT)).contains("5M/10M");
        assertThat(parsed.requestSignedWith(SECRET)).isTrue();
        assertThat(parsed.requestSignedWith("another-secret".getBytes(StandardCharsets.UTF_8))).isFalse();
    }

    @Test
    void N5_aReplyCountsOnlyWithTheSecretAndTheRequestItAnswers() {
        final byte[] request = RadiusPacket.request(RadiusPacket.DISCONNECT_REQUEST, 9, List.of(
                RadiusPacket.Attribute.string(RadiusPacket.ACCT_SESSION_ID, "81a00004")), SECRET);
        final byte[] requestAuthenticator = RadiusPacket.parse(request, request.length).authenticator();

        final byte[] ack = RadiusPacket.response(RadiusPacket.DISCONNECT_ACK, 9, requestAuthenticator, List.of(), SECRET);
        assertThat(RadiusPacket.parse(ack, ack.length).answers(requestAuthenticator, SECRET)).isTrue();

        final byte[] forged = RadiusPacket.response(RadiusPacket.DISCONNECT_ACK, 9, requestAuthenticator, List.of(),
                "guessed".getBytes(StandardCharsets.UTF_8));
        assertThat(RadiusPacket.parse(forged, forged.length).answers(requestAuthenticator, SECRET)).isFalse();

        final byte[] otherRequest = new byte[16];
        assertThat(RadiusPacket.parse(ack, ack.length).answers(otherRequest, SECRET)).isFalse();
    }

    @Test
    void N5_nakCarriesErrorCause() {
        final byte[] nak = RadiusPacket.response(RadiusPacket.COA_NAK, 1, new byte[16],
                List.of(RadiusPacket.Attribute.integer(RadiusPacket.ERROR_CAUSE, RadiusPacket.SESSION_CONTEXT_NOT_FOUND)), SECRET);
        assertThat(RadiusPacket.parse(nak, nak.length).integer(RadiusPacket.ERROR_CAUSE)).hasValue(503);
    }

    @Test
    void malformedDatagramsAreRejected() {
        assertThatThrownBy(() -> RadiusPacket.parse(new byte[10], 10)).isInstanceOf(IllegalArgumentException.class);
        final byte[] packet = RadiusPacket.request(RadiusPacket.COA_REQUEST, 1, List.of(
                RadiusPacket.Attribute.string(RadiusPacket.USER_NAME, "CB000001")), SECRET);
        packet[21] = (byte) 200; // attribute length past the end
        assertThatThrownBy(() -> RadiusPacket.parse(packet, packet.length)).isInstanceOf(IllegalArgumentException.class);
    }
    /** RFC 2865, section 7.1: secret "xyzzy5461", password "arctangent" and the authenticator given there. */
    @Test
    void ADR0019_passwordHidingMatchesTheRfcExample() {
        final byte[] authenticator = HexFormat.of().parseHex("0f403f9473978057bd83d5cb98f4227a");
        final byte[] hidden = RadiusPacket.hidePassword("arctangent".getBytes(StandardCharsets.UTF_8),
                "xyzzy5461".getBytes(StandardCharsets.UTF_8), authenticator);
        assertThat(HexFormat.of().formatHex(hidden)).isEqualTo("0dbe708d93d413ce3196e43f782a0aee");
    }

    @Test
    void ADR0019_anAccessRequestCarriesTheHiddenPasswordAndAValidMessageAuthenticator() throws Exception {
        final byte[] authenticator = HexFormat.of().parseHex("00112233445566778899aabbccddeeff");
        final byte[] packet = RadiusPacket.accessRequest(7, authenticator, "CBPR000001", "a-long-probe-password-fake-1",
                List.of(RadiusPacket.Attribute.address(RadiusPacket.NAS_IP_ADDRESS,
                        (java.net.Inet4Address) java.net.InetAddress.getByName("172.31.255.2"))), SECRET);
        final RadiusPacket parsed = RadiusPacket.parse(packet, packet.length);
        assertThat(parsed.code()).isEqualTo(RadiusPacket.ACCESS_REQUEST);
        assertThat(parsed.identifier()).isEqualTo(7);
        assertThat(parsed.authenticator()).isEqualTo(authenticator);
        assertThat(parsed.string(RadiusPacket.USER_NAME)).contains("CBPR000001");
        final byte[] hidden = parsed.attributes().stream().filter(a -> a.type() == RadiusPacket.USER_PASSWORD).findFirst()
                .orElseThrow().value();
        assertThat(hidden).hasSize(32).isEqualTo(RadiusPacket.hidePassword(
                "a-long-probe-password-fake-1".getBytes(StandardCharsets.UTF_8), SECRET, authenticator));
        assertThat(new String(hidden, StandardCharsets.ISO_8859_1)).doesNotContain("probe-password");
        assertThat(parsed.attributes().stream().filter(a -> a.type() == RadiusPacket.NAS_IP_ADDRESS).findFirst().orElseThrow().value())
                .isEqualTo(new byte[] {(byte) 172, 31, (byte) 255, 2});

        // RFC 3579: HMAC-MD5 over the packet with the Message-Authenticator zeroed, keyed by the secret.
        final byte[] zeroed = packet.clone();
        java.util.Arrays.fill(zeroed, zeroed.length - 16, zeroed.length, (byte) 0);
        assertThat(zeroed[zeroed.length - 18]).isEqualTo((byte) RadiusPacket.MESSAGE_AUTHENTICATOR);
        final javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacMD5");
        mac.init(new javax.crypto.spec.SecretKeySpec(SECRET, "HmacMD5"));
        assertThat(java.util.Arrays.copyOfRange(packet, packet.length - 16, packet.length)).isEqualTo(mac.doFinal(zeroed));
    }

    @Test
    void ADR0019_anAccessAcceptIsReadWithItsPool() {
        final byte[] authenticator = HexFormat.of().parseHex("00112233445566778899aabbccddeeff");
        final byte[] accept = RadiusPacket.response(RadiusPacket.ACCESS_ACCEPT, 7, authenticator,
                List.of(RadiusPacket.Attribute.string(RadiusPacket.FRAMED_POOL, "restricted")), SECRET);
        final RadiusPacket reply = RadiusPacket.parse(accept, accept.length);
        assertThat(reply.answers(authenticator, SECRET)).isTrue();
        assertThat(reply.answers(authenticator, "another-secret-fake".getBytes(StandardCharsets.UTF_8))).isFalse();
        assertThat(reply.string(RadiusPacket.FRAMED_POOL)).contains("restricted");
    }
}
