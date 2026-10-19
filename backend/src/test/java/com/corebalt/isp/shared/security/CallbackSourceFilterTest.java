package com.corebalt.isp.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CallbackSourceFilterTest {

    @Test
    void onlyAllowedNetworksPass() {
        final CallbackSourceFilter filter =
                new CallbackSourceFilter(List.of("196.201.214.200/32", " 172.16.0.0/12", "::1/128"));
        assertThat(filter.isAllowed("196.201.214.200")).isTrue();
        assertThat(filter.isAllowed("196.201.214.201")).isFalse();
        assertThat(filter.isAllowed("172.20.0.5")).isTrue();
        assertThat(filter.isAllowed("172.32.0.5")).isFalse();
        assertThat(filter.isAllowed("0:0:0:0:0:0:0:1")).isTrue();
        assertThat(filter.isAllowed("not-an-address")).isFalse();
    }
}
