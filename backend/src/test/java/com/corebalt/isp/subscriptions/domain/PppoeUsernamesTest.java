package com.corebalt.isp.subscriptions.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PppoeUsernamesTest {

    @Test
    void T2_firstLineIsTheCustomerNumberThenSuffixed() {
        assertThat(PppoeUsernames.forLine("CB001238", 1)).isEqualTo("CB001238");
        assertThat(PppoeUsernames.forLine("CB001238", 2)).isEqualTo("CB001238-2");
        assertThat(PppoeUsernames.forLine("CB001238", 10)).isEqualTo("CB001238-10");
        assertThatThrownBy(() -> PppoeUsernames.forLine("CB001238", 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
