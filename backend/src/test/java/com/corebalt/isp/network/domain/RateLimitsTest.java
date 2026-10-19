package com.corebalt.isp.network.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RateLimitsTest {

    @Test
    void N4_uploadThenDownloadInMegabitsWhenWhole() {
        assertThat(RateLimits.of(5000, 10000)).isEqualTo("5M/10M");
    }

    @Test
    void N4_kilobitsWhenNotAWholeMegabit() {
        assertThat(RateLimits.of(2500, 7500)).isEqualTo("2500k/7500k");
        assertThat(RateLimits.of(512, 1000)).isEqualTo("512k/1M");
    }

    @Test
    void N4_aSpeedIsPositive() {
        assertThatThrownBy(() -> RateLimits.of(0, 1000)).hasMessageContaining("N4");
    }
}
