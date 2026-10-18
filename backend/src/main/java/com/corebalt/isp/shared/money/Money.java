package com.corebalt.isp.shared.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * An amount of money in integer minor units (B2). All arithmetic on amounts goes through this type;
 * floating point is never used.
 *
 * <p>Credits are positive and charges negative (B3); this type allows both.
 */
public record Money(long minorUnits, Currency currency) implements Comparable<Money> {

    public static final Currency KES = Currency.getInstance("KES");

    public Money {
        Objects.requireNonNull(currency, "currency");
    }

    public static Money of(final long minorUnits, final Currency currency) {
        return new Money(minorUnits, currency);
    }

    public static Money ofMinor(final long minorUnits, final String currencyCode) {
        return new Money(minorUnits, Currency.getInstance(currencyCode));
    }

    /** Whole major units, e.g. {@code ofMajor(2500, KES)} is KES 2,500.00. */
    public static Money ofMajor(final long majorUnits, final Currency currency) {
        return new Money(Math.multiplyExact(majorUnits, factor(currency)), currency);
    }

    public static Money zero(final Currency currency) {
        return new Money(0, currency);
    }

    public Money plus(final Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(minorUnits, other.minorUnits), currency);
    }

    public Money minus(final Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(minorUnits, other.minorUnits), currency);
    }

    public Money negate() {
        return new Money(Math.negateExact(minorUnits), currency);
    }

    public Money times(final long factor) {
        return new Money(Math.multiplyExact(minorUnits, factor), currency);
    }

    /**
     * {@code this × numerator ÷ denominator}, rounded up to the next whole major unit (shilling).
     * Used for proration (B6): the customer is never undercharged by a fraction of a shilling.
     * Exact integer arithmetic; a negative result rounds towards positive infinity.
     */
    public Money multiplyRatioRoundUpToMajor(final long numerator, final long denominator) {
        if (denominator <= 0) {
            throw new IllegalArgumentException("denominator must be positive");
        }
        final BigDecimal factor = BigDecimal.valueOf(factor(currency));
        final BigDecimal majorUnits =
                BigDecimal.valueOf(minorUnits)
                        .multiply(BigDecimal.valueOf(numerator))
                        .divide(BigDecimal.valueOf(denominator).multiply(factor), 0, RoundingMode.CEILING);
        return new Money(majorUnits.multiply(factor).longValueExact(), currency);
    }

    /** Rounds this amount up to the next whole major unit, e.g. KES 10.01 → KES 11.00. */
    public Money roundUpToMajor() {
        return multiplyRatioRoundUpToMajor(1, 1);
    }

    /**
     * Proration for an immediate upgrade (B6): {@code (newPrice − oldPrice) × remainingDays ÷
     * durationDays}, rounded up to the shilling, never negative.
     */
    public static Money proration(
            final Money oldPrice, final Money newPrice, final long remainingDays, final long durationDays) {
        if (remainingDays < 0 || durationDays <= 0) {
            throw new IllegalArgumentException("B6: remainingDays >= 0 and durationDays > 0 required");
        }
        final Money difference = newPrice.minus(oldPrice);
        if (!difference.isPositive() || remainingDays == 0) {
            return zero(newPrice.currency());
        }
        return difference.multiplyRatioRoundUpToMajor(Math.min(remainingDays, durationDays), durationDays);
    }

    public boolean isPositive() {
        return minorUnits > 0;
    }

    public boolean isNegative() {
        return minorUnits < 0;
    }

    public boolean isZero() {
        return minorUnits == 0;
    }

    public boolean isGreaterThanOrEqualTo(final Money other) {
        return compareTo(other) >= 0;
    }

    @Override
    public int compareTo(final Money other) {
        requireSameCurrency(other);
        return Long.compare(minorUnits, other.minorUnits);
    }

    /** Decimal major-unit view for display only; never feed it back into arithmetic. */
    public BigDecimal toMajor() {
        return BigDecimal.valueOf(minorUnits, currency.getDefaultFractionDigits());
    }

    @Override
    public String toString() {
        return currency.getCurrencyCode() + " " + toMajor().toPlainString();
    }

    private void requireSameCurrency(final Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "Currency mismatch: " + currency + " vs " + other.currency);
        }
    }

    private static long factor(final Currency currency) {
        return BigDecimal.TEN.pow(Math.max(currency.getDefaultFractionDigits(), 0)).longValueExact();
    }
}
