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
}
