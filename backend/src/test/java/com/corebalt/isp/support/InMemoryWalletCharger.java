package com.corebalt.isp.support;

import com.corebalt.isp.shared.money.Money;
import com.corebalt.isp.subscriptions.api.WalletCharger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test stand-in for the billing module's wallet (step 5): balances per customer in memory, charged
 * under one lock so two renewals cannot both spend one balance (B3, B5). Not transactional: tests
 * that need rollback behaviour check the subscription, not this map.
 */
public class InMemoryWalletCharger implements WalletCharger {

    private final Map<UUID, Long> balances = new ConcurrentHashMap<>();
    private final List<String> references = new ArrayList<>();

    public synchronized void setBalance(final UUID customerId, final long minorUnits) {
        balances.put(customerId, minorUnits);
    }

    public synchronized long balance(final UUID customerId) {
        return balances.getOrDefault(customerId, 0L);
    }

    public synchronized List<String> references() {
        return List.copyOf(references);
    }

    @Override
    public synchronized Outcome charge(final UUID customerId, final Money amount, final String reference) {
        final long balance = balance(customerId);
        if (balance < amount.minorUnits()) {
            return Outcome.INSUFFICIENT_BALANCE;
        }
        balances.put(customerId, balance - amount.minorUnits());
        references.add(reference);
        return Outcome.CHARGED;
    }
}
