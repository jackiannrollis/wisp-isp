package com.corebalt.isp.shared.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Currency;
import org.junit.jupiter.api.Test;

class MoneyTest {

    private static final Currency KES = Money.KES;
    private static final Currency USD = Currency.getInstance("USD");

    @Test
    void B2_amountsAreIntegerMinorUnits() {
        assertThat(Money.ofMajor(2_500, KES).minorUnits()).isEqualTo(250_000);
        assertThat(Money.ofMinor(250_000, "KES")).isEqualTo(Money.ofMajor(2_500, KES));
        assertThat(Money.ofMinor(1_050, "KES")).hasToString("KES 10.50");
    }

    @Test
    void arithmeticIsExact() {
        final Money a = Money.of(1_001, KES);
        final Money b = Money.of(999, KES);
        assertThat(a.plus(b)).isEqualTo(Money.of(2_000, KES));
        assertThat(b.minus(a)).isEqualTo(Money.of(-2, KES));
        assertThat(a.negate()).isEqualTo(Money.of(-1_001, KES));
        assertThat(a.times(3)).isEqualTo(Money.of(3_003, KES));
    }

    @Test
    void B3_creditsArePositiveAndChargesNegative() {
        assertThat(Money.of(100, KES).isPositive()).isTrue();
        assertThat(Money.of(-100, KES).isNegative()).isTrue();
        assertThat(Money.zero(KES).isZero()).isTrue();
    }

    @Test
    void overflowFailsInsteadOfWrapping() {
        assertThatThrownBy(() -> Money.of(Long.MAX_VALUE, KES).plus(Money.of(1, KES)))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void mixingCurrenciesFails() {
        assertThatThrownBy(() -> Money.of(1, KES).plus(Money.of(1, USD)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Currency mismatch");
        assertThatThrownBy(() -> Money.of(1, KES).compareTo(Money.of(1, USD)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void roundUpToMajorGoesToTheNextShilling() {
        assertThat(Money.of(1_001, KES).roundUpToMajor()).isEqualTo(Money.of(1_100, KES));
        assertThat(Money.of(1_000, KES).roundUpToMajor()).isEqualTo(Money.of(1_000, KES));
        assertThat(Money.of(1, KES).roundUpToMajor()).isEqualTo(Money.of(100, KES));
        assertThat(Money.of(-150, KES).roundUpToMajor()).isEqualTo(Money.of(-100, KES));
    }

    @Test
    void B6_prorationRoundsUpToTheShilling() {
        // (KES 2,500 − KES 1,500) × 10 ÷ 30 = KES 333.33… → KES 334
        final Money charge =
                Money.proration(Money.ofMajor(1_500, KES), Money.ofMajor(2_500, KES), 10, 30);
        assertThat(charge).isEqualTo(Money.ofMajor(334, KES));
    }

    @Test
    void B6_prorationExactWhenItDividesEvenly() {
        final Money charge =
                Money.proration(Money.ofMajor(1_500, KES), Money.ofMajor(3_000, KES), 15, 30);
        assertThat(charge).isEqualTo(Money.ofMajor(750, KES));
    }

    @Test
    void B6_prorationIsNeverNegative() {
        assertThat(Money.proration(Money.ofMajor(2_500, KES), Money.ofMajor(1_500, KES), 10, 30))
                .isEqualTo(Money.zero(KES));
        assertThat(Money.proration(Money.ofMajor(1_500, KES), Money.ofMajor(2_500, KES), 0, 30))
                .isEqualTo(Money.zero(KES));
    }

    @Test
    void B6_prorationNeverExceedsTheFullDifference() {
        assertThat(Money.proration(Money.ofMajor(1_500, KES), Money.ofMajor(2_500, KES), 45, 30))
                .isEqualTo(Money.ofMajor(1_000, KES));
    }

    @Test
    void B6_prorationRejectsInvalidDurations() {
        assertThatThrownBy(
                        () -> Money.proration(Money.ofMajor(1, KES), Money.ofMajor(2, KES), 1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("B6");
    }
}
