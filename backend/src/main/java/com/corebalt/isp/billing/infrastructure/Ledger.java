package com.corebalt.isp.billing.infrastructure;

import com.corebalt.isp.shared.id.UuidV7;
import com.corebalt.isp.shared.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only writer of {@code billing_ledger_entry} and of wallet balances (B1, B3, B4). Each post
 * creates the wallet if needed, row-locks it, refuses a second entry for the same cause, writes
 * the entry with its running balance and updates the cached balance, all in the caller's
 * transaction. Entries are never updated or deleted; the application role cannot.
 */
@Component
public class Ledger {

    private final JdbcClient jdbc;
    private final Clock clock;

    Ledger(final JdbcClient jdbc, final Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** One entry to write. {@code amountMinor} is signed: credits positive, charges negative (B3). */
    public record Posting(
            UUID customerId,
            String currency,
            String type,
            long amountMinor,
            String referenceType,
            UUID referenceId,
            String description,
            String reason,
            UUID createdBy,
            boolean mayGoNegative) {}

    public sealed interface Result {}

    public record Posted(UUID entryId, UUID walletId, long balanceAfterMinor, Instant at) implements Result {}

    /** The cause already has an entry; nothing was written. */
    public record Duplicate(UUID entryId) implements Result {}

    /** The balance does not cover the debit; nothing was written. */
    public record Insufficient(long balanceMinor) implements Result {}

    @Transactional(propagation = Propagation.MANDATORY)
    public Result post(final Posting posting) {
        final Wallet wallet = lockWallet(posting.customerId(), posting.currency());
        final Optional<UUID> existing =
                jdbc.sql("select id from billing_ledger_entry where reference_type = :type and reference_id = :id")
                        .param("type", posting.referenceType())
                        .param("id", posting.referenceId())
                        .query(UUID.class)
                        .optional();
        if (existing.isPresent()) {
            return new Duplicate(existing.get());
        }
        final long balanceAfter = Math.addExact(wallet.balanceMinor(), posting.amountMinor());
        if (balanceAfter < 0 && posting.amountMinor() < 0 && !posting.mayGoNegative()) {
            return new Insufficient(wallet.balanceMinor());
        }
        final UUID entryId = UuidV7.next(clock);
        final Instant now = clock.instant();
        jdbc.sql(
                        """
                        insert into billing_ledger_entry
                            (id, tenant_id, wallet_id, type, amount_minor, balance_after_minor, currency,
                             reference_type, reference_id, description, reason, created_by, created_at)
                        values (:id, :tenantId, :walletId, :type, :amount, :balanceAfter, :currency,
                                :referenceType, :referenceId, :description, :reason, :createdBy, :now)
                        """)
                .param("id", entryId)
                .param("tenantId", TenantContext.requireTenantId())
                .param("walletId", wallet.id())
                .param("type", posting.type())
                .param("amount", posting.amountMinor())
                .param("balanceAfter", balanceAfter)
                .param("currency", posting.currency())
                .param("referenceType", posting.referenceType())
                .param("referenceId", posting.referenceId())
                .param("description", posting.description())
                .param("reason", posting.reason())
                .param("createdBy", posting.createdBy())
                .param("now", Timestamp.from(now))
                .update();
        jdbc.sql("update billing_wallet set balance_minor = :balance, updated_at = :now where id = :id")
                .param("balance", balanceAfter)
                .param("now", Timestamp.from(now))
                .param("id", wallet.id())
                .update();
        return new Posted(entryId, wallet.id(), balanceAfter, now);
    }

    /** The cached balance without locking; zero without a wallet. For display and decisions only. */
    public long balance(final UUID customerId) {
        return jdbc.sql("select balance_minor from billing_wallet where customer_id = :customerId")
                .param("customerId", customerId)
                .query(Long.class)
                .optional()
                .orElse(0L);
    }

    public boolean hasEntryFor(final String referenceType, final UUID referenceId) {
        return jdbc.sql("select count(*) from billing_ledger_entry where reference_type = :type and reference_id = :id")
                        .param("type", referenceType)
                        .param("id", referenceId)
                        .query(Long.class)
                        .single()
                > 0;
    }

    /** B4: wallets whose cached balance differs from their latest entry's running balance. */
    public List<Drift> drift() {
        return jdbc.sql(
                        """
                        select w.id, w.customer_id, w.balance_minor,
                               coalesce((select e.balance_after_minor from billing_ledger_entry e
                                         where e.wallet_id = w.id order by e.created_at desc, e.id desc limit 1), 0) as ledger,
                               coalesce((select sum(e.amount_minor) from billing_ledger_entry e where e.wallet_id = w.id), 0) as total
                        from billing_wallet w
                        """)
                .query((rs, n) -> new Drift(
                        rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getLong(3), rs.getLong(4), rs.getLong(5)))
                .list()
                .stream()
                .filter(Drift::drifted)
                .toList();
    }

    /** {@code cachedMinor} is the wallet's balance, {@code lastEntryMinor} the latest running balance, {@code sumMinor} all entries. */
    public record Drift(UUID walletId, UUID customerId, long cachedMinor, long lastEntryMinor, long sumMinor) {

        boolean drifted() {
            return cachedMinor != lastEntryMinor || cachedMinor != sumMinor;
        }
    }

    private Wallet lockWallet(final UUID customerId, final String currency) {
        jdbc.sql(
                        """
                        insert into billing_wallet (id, tenant_id, customer_id, currency)
                        values (:id, :tenantId, :customerId, :currency)
                        on conflict (tenant_id, customer_id) do nothing
                        """)
                .param("id", UuidV7.next(clock))
                .param("tenantId", TenantContext.requireTenantId())
                .param("customerId", customerId)
                .param("currency", currency)
                .update();
        final Wallet wallet =
                jdbc.sql("select id, balance_minor, currency from billing_wallet where customer_id = :customerId for update")
                        .param("customerId", customerId)
                        .query((rs, n) -> new Wallet(rs.getObject(1, UUID.class), rs.getLong(2), rs.getString(3)))
                        .single();
        if (!wallet.currency().equals(currency)) {
            throw new IllegalStateException("B2: wallet currency " + wallet.currency() + " cannot take " + currency);
        }
        return wallet;
    }

    private record Wallet(UUID id, long balanceMinor, String currency) {}
}
