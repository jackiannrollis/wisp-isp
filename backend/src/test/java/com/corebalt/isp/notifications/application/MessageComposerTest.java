package com.corebalt.isp.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MessageComposerTest {

    @Test
    void Q15_allowancesReadInDecimalGigabytes() {
        assertThat(MessageComposer.gigabytes(100_000_000_000L)).isEqualTo("100 GB");
        assertThat(MessageComposer.gigabytes(1_500_000_000L)).isEqualTo("1.5 GB");
        assertThat(MessageComposer.gigabytes(1_250_000_000L)).isEqualTo("1.3 GB");
        assertThat(MessageComposer.gigabytes(2_000_000_000L)).isEqualTo("2 GB");
    }
}
