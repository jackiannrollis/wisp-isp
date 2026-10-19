package com.corebalt.isp.payments.infrastructure;

import com.corebalt.isp.payments.domain.Payment;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Row-level security limits every query to the current tenant (T1). */
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    boolean existsByProviderTxnId(String providerTxnId);

    Optional<Payment> findByProviderTxnId(String providerTxnId);

    @Query("select p from Payment p where p.status = 'UNMATCHED' order by p.receivedAt desc")
    Page<Payment> findUnmatched(Pageable page);

    /** Provider transaction IDs recorded in a period, for reconciliation. */
    @Query("select p.providerTxnId from Payment p where p.receivedAt >= :from and p.receivedAt < :to "
            + "and p.channel in ('MPESA_PAYBILL', 'MPESA_STK')")
    List<String> findMpesaReferencesBetween(Instant from, Instant to);

    Page<Payment> findByCustomerId(UUID customerId, Pageable page);

    @Query("select p from Payment p where p.status = 'MATCHED' and p.matchedAt >= :from and p.matchedAt < :to")
    List<Payment> findMatchedBetween(Instant from, Instant to);
}
