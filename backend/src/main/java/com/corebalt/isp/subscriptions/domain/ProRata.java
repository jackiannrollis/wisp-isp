package com.corebalt.isp.subscriptions.domain;

import com.corebalt.isp.shared.money.Money;
import java.util.Optional;

/**
 * The {@code PRO_RATA_DAYS} partial-payment rule (docs/05 Partial payments): days granted =
 * floor(balance ÷ (price ÷ duration days)); the charge is those days at the daily rate, rounded up
 * to the shilling like every fractional charge (B6), so the tenant is never short by a cent. If the
 * rounding would exceed the balance, one day fewer is granted. Exact integer arithmetic (B2).
 */
public final class ProRata {

    private ProRata() {}

    public record Grant(int days, Money charge) {}

    /** Empty when the balance buys less than a day, or the package is shorter than a day. */
    public static Optional<Grant> grant(final Money balance, final Money price, final int durationDays) {
        if (durationDays < 1 || !price.isPositive() || !balance.isPositive()) {
            return Optional.empty();
        }
        long days = Math.min(
                Math.multiplyExact(balance.minorUnits(), (long) durationDays) / price.minorUnits(), durationDays - 1L);
        while (days >= 1) {
            final Money charge = price.multiplyRatioRoundUpToMajor(days, durationDays);
            if (balance.isGreaterThanOrEqualTo(charge)) {
                return Optional.of(new Grant((int) days, charge));
            }
            days--;
        }
        return Optional.empty();
    }
}
