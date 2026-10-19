package com.corebalt.isp.subscriptions.infrastructure;

import com.corebalt.isp.subscriptions.domain.Subscription;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Row-level security limits every query to the current tenant (T1). */
public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

    long countByCustomerId(UUID customerId);

    Page<Subscription> findByCustomerId(UUID customerId, Pageable page);
}
