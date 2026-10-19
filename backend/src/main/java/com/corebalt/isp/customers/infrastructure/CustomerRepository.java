package com.corebalt.isp.customers.infrastructure;

import com.corebalt.isp.customers.domain.Customer;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

/** Row-level security limits every query to the current tenant (T1). */
public interface CustomerRepository extends JpaRepository<Customer, UUID>, JpaSpecificationExecutor<Customer> {

    Optional<Customer> findByNumber(String number);

    List<Customer> findByPhone(String phone);

    @Query(value = "select * from customers_customer where phone_sha256 = :hash", nativeQuery = true)
    List<Customer> findByPhoneSha256(String hash);

    /** Phones are E.164; the digits after '+' are the 2547... form Daraja shows. */
    @Query(value = "select * from customers_customer where substr(phone, 2) like :prefix || '%' and substr(phone, 2) like '%' || :suffix "
            + "order by number limit 20", nativeQuery = true)
    List<Customer> findByPhoneDigits(String prefix, String suffix);

    @Query("select c.phone from Customer c where c.phone in :phones")
    List<String> findPhonesIn(Collection<String> phones);
}
