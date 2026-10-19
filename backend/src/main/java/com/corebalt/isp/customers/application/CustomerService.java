package com.corebalt.isp.customers.application;

import com.corebalt.isp.customers.api.CustomerCreated;
import com.corebalt.isp.customers.api.CustomerDto;
import com.corebalt.isp.customers.api.CustomerFacade;
import com.corebalt.isp.customers.api.CustomerRequest;
import com.corebalt.isp.customers.api.CustomerSummary;
import com.corebalt.isp.customers.domain.Customer;
import com.corebalt.isp.customers.domain.CustomerStatus;
import com.corebalt.isp.customers.domain.PhoneNumbers;
import com.corebalt.isp.customers.infrastructure.CustomerNumberAllocator;
import com.corebalt.isp.customers.infrastructure.CustomerRepository;
import com.corebalt.isp.customers.infrastructure.CustomerSpecifications;
import com.corebalt.isp.shared.id.UuidV7;
import com.corebalt.isp.shared.outbox.OutboxPublisher;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.shared.web.InvalidRequestException;
import com.corebalt.isp.shared.web.NotFoundException;
import com.corebalt.isp.shared.web.PageResponse;
import com.corebalt.isp.tenancy.api.TenantProfile;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Customer commands and queries (docs/03 customers, T2). */
@Service
public class CustomerService implements CustomerFacade {

    private final CustomerRepository customers;
    private final CustomerNumberAllocator numbers;
    private final TenantProfile tenantProfile;
    private final OutboxPublisher outbox;
    private final CustomerMapper mapper;
    private final Clock clock;

    CustomerService(
            final CustomerRepository customers,
            final CustomerNumberAllocator numbers,
            final TenantProfile tenantProfile,
            final OutboxPublisher outbox,
            final CustomerMapper mapper,
            final Clock clock) {
        this.customers = customers;
        this.numbers = numbers;
        this.tenantProfile = tenantProfile;
        this.outbox = outbox;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    @Transactional
    public CustomerDto register(final CustomerRequest request) {
        final Customer.Profile profile = profile(request);
        final Instant now = clock.instant();
        final Customer customer =
                Customer.register(
                        UuidV7.next(clock),
                        TenantContext.requireTenantId(),
                        numbers.next(tenantProfile.currentTenantCode()),
                        profile,
                        now);
        customers.save(customer);
        outbox.publish(
                new CustomerCreated(
                        UuidV7.next(clock), customer.getTenantId(), now, customer.getId(), customer.getNumber()));
        return mapper.toDto(customer);
    }

    @Transactional
    public CustomerDto update(final UUID customerId, final CustomerRequest request) {
        final Customer customer = load(customerId);
        customer.update(profile(request), clock.instant());
        return mapper.toDto(customer);
    }

    @Transactional
    public CustomerDto archive(final UUID customerId) {
        final Customer customer = load(customerId);
        customer.archive(clock.instant());
        return mapper.toDto(customer);
    }

    @Transactional(readOnly = true)
    public CustomerDto get(final UUID customerId) {
        return mapper.toDto(load(customerId));
    }

    @Transactional(readOnly = true)
    public PageResponse<CustomerDto> list(final String query, final CustomerStatus status, final Pageable page) {
        return PageResponse.of(
                customers.findAll(
                        CustomerSpecifications.matching(query).and(CustomerSpecifications.withStatus(status)),
                        page),
                mapper::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CustomerSummary> find(final UUID customerId) {
        return customers.findById(customerId)
                .map(c -> new CustomerSummary(
                        c.getId(), c.getNumber(), c.getName(), c.getStatus() == CustomerStatus.ACTIVE));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> phoneOf(final UUID customerId) {
        return customers.findById(customerId).map(Customer::getPhone);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CustomerSummary> findByNumber(final String number) {
        return customers.findByNumber(number).map(CustomerService::summary);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CustomerSummary> findByPhone(final String e164) {
        return customers.findByPhone(e164).stream().map(CustomerService::summary).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<CustomerSummary> findByPhoneHash(final String sha256Hex) {
        return customers.findByPhoneSha256(sha256Hex.toLowerCase(java.util.Locale.ROOT)).stream()
                .map(CustomerService::summary).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<CustomerSummary> findByPhoneDigits(final String prefix, final String suffix) {
        if (!prefix.matches("[0-9]*") || !suffix.matches("[0-9]*") || prefix.length() + suffix.length() < 6) {
            return List.of();
        }
        return customers.findByPhoneDigits(prefix, suffix).stream().map(CustomerService::summary).toList();
    }

    private static CustomerSummary summary(final Customer c) {
        return new CustomerSummary(c.getId(), c.getNumber(), c.getName(), c.getStatus() == CustomerStatus.ACTIVE);
    }

    @Override
    public Optional<String> normalisePhone(final String phone) {
        return PhoneNumbers.normalise(phone);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> existingPhones(final Collection<String> phones) {
        return phones.isEmpty() ? Set.of() : new HashSet<>(customers.findPhonesIn(phones));
    }

    private Customer load(final UUID customerId) {
        return customers.findById(customerId).orElseThrow(() -> new NotFoundException("Customer"));
    }

    private static Customer.Profile profile(final CustomerRequest request) {
        final String phone =
                PhoneNumbers.normalise(request.phone())
                        .orElseThrow(() -> InvalidRequestException.of("phone", "not a valid phone number"));
        return new Customer.Profile(
                request.name().strip(),
                phone,
                blankToNull(request.email()),
                blankToNull(request.address()),
                blankToNull(request.installationLocation()),
                request.lat(),
                request.lng());
    }

    private static String blankToNull(final String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
