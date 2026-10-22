package com.corebalt.isp.notifications.application;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Staging's SMS allowlist (decisions.md 2026-10-21): when {@code isp.sms.allowlist} lists numbers, an
 * SMS goes out only to them; every other message is kept as SUPPRESSED with the reason, never sent.
 * Empty (production) means no restriction. Numbers are E.164 (+2547...), compared exactly.
 */
@Component
public class SmsAllowlist {

    static final String REASON = "Not on this environment's SMS allowlist";

    private final Set<String> numbers;

    SmsAllowlist(@Value("${isp.sms.allowlist:}") final String numbers) {
        this.numbers = Arrays.stream(numbers.split(","))
                .map(String::strip)
                .filter(n -> !n.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean permits(final String e164) {
        return numbers.isEmpty() || numbers.contains(e164);
    }

    public boolean active() {
        return !numbers.isEmpty();
    }
}
