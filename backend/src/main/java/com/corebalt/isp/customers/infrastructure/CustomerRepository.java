package com.corebalt.isp.customers.infrastructure;

import com.corebalt.isp.customers.domain.Customer;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

/** Row-level security limits every query to the current tenant (T1). */
public interface CustomerRepository extends JpaRepository<Customer, UUID>, JpaSpecificationExecutor<Customer> {

    @Query("select c.phone from Customer c where c.phone in :phones")
    List<String> findPhonesIn(Collection<String> phones);
}
