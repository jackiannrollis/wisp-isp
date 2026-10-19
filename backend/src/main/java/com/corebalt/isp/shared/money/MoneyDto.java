package com.corebalt.isp.shared.money;

import com.corebalt.isp.shared.web.InvalidRequestException;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Money on the API: {@code {"amount": 250000, "currency": "KES"}} in minor units (conventions.md, API). */
public record MoneyDto(@NotNull Long amount, @NotNull @Pattern(regexp = "[A-Z]{3}") String currency) {

    public static MoneyDto of(final Money money) {
        return new MoneyDto(money.minorUnits(), money.currency().getCurrencyCode());
    }

    public Money toMoney() {
        try {
            return Money.ofMinor(amount, currency);
        } catch (IllegalArgumentException e) {
            throw InvalidRequestException.of("currency", "unknown currency code");
        }
    }
}
