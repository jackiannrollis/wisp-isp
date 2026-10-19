package com.corebalt.isp.subscriptions.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.corebalt.isp.shared.money.Money;
import org.junit.jupiter.api.Test;

class ProRataTest {

    static final Money PRICE = Money.ofMinor(250_000, "KES"); // KES 2,500 for 30 days: 83.33 a day

    @Test
    void daysAreWhatTheBalanceBuysAtTheDailyRate() {
        // KES 1,000 buys floor(1000 / 83.33) = 12 days, charged 12 × 83.33 = 1000.00 rounded up to 1,000.
        final ProRata.Grant grant = ProRata.grant(Money.ofMinor(100_000, "KES"), PRICE, 30).orElseThrow();
        assertThat(grant.days()).isEqualTo(12);
        assertThat(grant.charge()).isEqualTo(Money.ofMinor(100_000, "KES"));
    }

    @Test
    void B6_roundingUpToTheShillingNeverExceedsTheBalance() {
        // KES 900 buys 10 days (833.33, rounded up to 834); the rest stays in the wallet.
        final ProRata.Grant grant = ProRata.grant(Money.ofMinor(90_000, "KES"), PRICE, 30).orElseThrow();
        assertThat(grant.days()).isEqualTo(10);
        assertThat(grant.charge()).isEqualTo(Money.ofMinor(83_400, "KES"));
    }

    @Test
    void lessThanADayBuysNothing() {
        assertThat(ProRata.grant(Money.ofMinor(8_000, "KES"), PRICE, 30)).isEmpty();
        assertThat(ProRata.grant(Money.ofMinor(100_000, "KES"), PRICE, 0)).isEmpty();
    }
}
