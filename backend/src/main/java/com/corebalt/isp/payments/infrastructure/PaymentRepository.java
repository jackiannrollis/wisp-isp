package com.corebalt.isp.payments.infrastructure;

import com.corebalt.isp.payments.domain.Payment;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Row-level security limits every query to the current tenant (T1). */
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    boolean existsByProviderTxnId(String providerTxnId);

    Page<Payment> findByCustomerId(UUID customerId, Pageable page);

    @Query("select p from Payment p where p.status = 'MATCHED' and p.matchedAt >= :from and p.matchedAt < :to")
    List<Payment> findMatchedBetween(Instant from, Instant to);
}
