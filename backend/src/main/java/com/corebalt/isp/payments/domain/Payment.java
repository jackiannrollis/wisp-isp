package com.corebalt.isp.payments.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Money received (docs/03 payments). The raw payload is stored before anything else and never
 * changed (P2); (tenant, provider transaction ID) is unique (P1).
 */
@Entity
@Table(name = "payments_payment")
public class Payment {

    @Id private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private String channel;

    @Column(name = "provider_txn_id", nullable = false, updatable = false)
    private String providerTxnId;

    @Column(name = "payer_name", updatable = false)
    private String payerName;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(nullable = false, updatable = false)
    private String currency;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(nullable = false)
    private String status;

    @Column(name = "customer_id")
    private UUID customerId;

    @Column(name = "matched_by")
    private UUID matchedBy;

    @Column(name = "matched_at")
    private Instant matchedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload", nullable = false, updatable = false)
    private String rawPayload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Payment() {}

    /** A payment staff recorded for a known customer: matched on entry (P5). */
    public static Payment recordedManually(
            final UUID id,
            final UUID tenantId,
            final String channel,
            final String reference,
            final String payerName,
            final long amountMinor,
            final String currency,
            final Instant receivedAt,
            final UUID customerId,
            final UUID staffId,
            final String rawPayload,
            final Instant now) {
        final Payment payment = new Payment();
        payment.id = id;
        payment.tenantId = tenantId;
        payment.channel = channel;
        payment.providerTxnId = reference;
        payment.payerName = payerName;
        payment.amountMinor = amountMinor;
        payment.currency = currency;
        payment.receivedAt = receivedAt;
        payment.status = "MATCHED";
        payment.customerId = customerId;
        payment.matchedBy = staffId;
        payment.matchedAt = now;
        payment.rawPayload = rawPayload;
        payment.createdAt = now;
        payment.updatedAt = now;
        return payment;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getChannel() {
        return channel;
    }

    public String getProviderTxnId() {
        return providerTxnId;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public String getStatus() {
        return status;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public Instant getMatchedAt() {
        return matchedAt;
    }
}
