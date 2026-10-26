package com.corebalt.isp.network;

import static org.assertj.core.api.Assertions.assertThat;

import com.corebalt.isp.network.domain.WireGuardKeys;
import java.util.Base64;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/** ADR 0013: the platform derives a router's WireGuard public key the way {@code wg pubkey} does. */
class WireGuardKeysTest {

    // RFC 7748 section 6.1, Alice: her private scalar is not pre-clamped in the RFC.
    private static final byte[] ALICE_PRIVATE =
            HexFormat.of().parseHex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a");
    private static final String ALICE_PUBLIC =
            Base64.getEncoder().encodeToString(
                    HexFormat.of().parseHex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"));

    @Test
    void publicKeyMatchesTheRfc7748VectorFromTheRawAndTheClampedScalar() {
        assertThat(WireGuardKeys.publicKeyOf(Base64.getEncoder().encodeToString(ALICE_PRIVATE))).isEqualTo(ALICE_PUBLIC);
        assertThat(WireGuardKeys.publicKeyOf(Base64.getEncoder().encodeToString(WireGuardKeys.clamp(ALICE_PRIVATE))))
                .as("clamping before emission changes nothing: X25519 clamps on use")
                .isEqualTo(ALICE_PUBLIC);
    }

    @Test
    void generatedKeysAreWireGuardShapedAndConsistent() {
        final WireGuardKeys.Pair pair = WireGuardKeys.generate();
        assertThat(pair.privateKey()).matches("[A-Za-z0-9+/]{43}=");
        assertThat(pair.publicKey()).matches("[A-Za-z0-9+/]{43}=");
        assertThat(WireGuardKeys.publicKeyOf(pair.privateKey())).isEqualTo(pair.publicKey());
        final byte[] scalar = Base64.getDecoder().decode(pair.privateKey());
        assertThat(scalar[0] & 7).isZero();
        assertThat(scalar[31] & 128).isZero();
        assertThat(scalar[31] & 64).isEqualTo(64);
        assertThat(WireGuardKeys.generate().privateKey()).isNotEqualTo(pair.privateKey());
    }
}
