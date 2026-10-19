package com.corebalt.isp.network.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SecretGeneratorTest {

    @Test
    void ADR0009_passwordsAreLongAndFreeOfLookAlikesAndQuotingTrouble() {
        final String password = SecretGenerator.pppoePassword();
        assertThat(password).hasSize(16).matches("[A-HJ-NP-Za-km-z2-9]+");
        assertThat(SecretGenerator.sharedSecret()).hasSize(32).matches("[A-HJ-NP-Za-km-z2-9]+");
    }

    @Test
    void valuesDoNotRepeat() {
        final Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            assertThat(seen.add(SecretGenerator.pppoePassword())).isTrue();
        }
    }
}
