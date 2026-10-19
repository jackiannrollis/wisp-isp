package com.corebalt.isp.identity.application;

import com.corebalt.isp.customers.api.CustomerFacade;
import com.corebalt.isp.customers.api.CustomerSummary;
import com.corebalt.isp.identity.domain.Credentials;
import com.corebalt.isp.notifications.api.OneTimeCodes;
import com.corebalt.isp.shared.audit.AuditEntry;
import com.corebalt.isp.shared.audit.AuditTrail;
import com.corebalt.isp.shared.id.UuidV7;
import com.corebalt.isp.shared.security.Tokens;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.shared.web.InvalidRequestException;
import com.corebalt.isp.tenancy.api.TenantRegistry;
import com.corebalt.isp.tenancy.api.TenantRegistry.TenantRef;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Portal sign-in (ADR 0010; Q7 default: PIN, with an SMS code to set or reset it). A code is 6
 * digits, valid 5 minutes, 3 attempts, then the customer asks for a new one; codes are stored hashed.
 * Sending is limited per phone (3 in 10 minutes, 10 a day, counted in the database) and per client
 * address (Redis); a tenant sending more than {@link #BURST} codes in 5 minutes raises an alert. The
 * PIN is Argon2id-hashed; 5 wrong PINs lock the account for 15 minutes. Whether a phone belongs to a
 * customer is never revealed.
 */
@Service
public class PortalSignIn {

    private static final Logger log = LoggerFactory.getLogger(PortalSignIn.class);

    static final Duration CODE_TTL = Duration.ofMinutes(5);
    static final int CODE_ATTEMPTS = 3;
    static final int PHONE_PER_10_MIN = 3;
    static final int PHONE_PER_DAY = 10;
    /** Per client address: higher than per phone, as many customers share one carrier-grade NAT address. */
    static final int IP_PER_10_MIN = 10;
    static final int IP_PER_DAY = 50;
    static final int BURST = 30;
    static final String WRONG = "Wrong account or PIN.";

    /** The answer to a code request is the same whether or not the phone is a customer's. */
    public record CodeRequested(String message) {}

    public static class TooManyRequests extends RuntimeException {
        public TooManyRequests(final String message) {
            super(message);
        }
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final TenantRegistry tenants;
    private final CustomerFacade customers;
    private final OneTimeCodes codes;
    private final TokenIssuer tokens;
    private final RefreshTokens refreshTokens;
    private final TokenVersions versions;
    private final AuditTrail audit;
    private final StringRedisTemplate redis;
    private final MeterRegistry meters;
    private final Clock clock;

    PortalSignIn(
            final JdbcClient jdbc,
            final TransactionTemplate tx,
            final TenantRegistry tenants,
            final CustomerFacade customers,
            final OneTimeCodes codes,
            final TokenIssuer tokens,
            final RefreshTokens refreshTokens,
            final TokenVersions versions,
            final AuditTrail audit,
            final StringRedisTemplate redis,
            final MeterRegistry meters,
            final Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.tenants = tenants;
        this.customers = customers;
        this.codes = codes;
        this.tokens = tokens;
        this.refreshTokens = refreshTokens;
        this.versions = versions;
        this.audit = audit;
        this.redis = redis;
        this.meters = meters;
        this.clock = clock;
    }

    public CodeRequested requestCode(final String tenantKey, final String phone, final String clientAddress) {
        final CodeRequested answer = new CodeRequested(
                "If this number belongs to an account, a code is on its way. It expires in 5 minutes.");
        limitAddress(clientAddress);
        final Optional<TenantRef> tenant = tenants.findByCodeOrSubdomain(tenantKey).filter(TenantRef::canSignIn);
        if (tenant.isEmpty()) {
            return answer;
        }
        TenantContext.runAsTenant(tenant.get().id(), () -> {
            final String e164 = tx.execute(s -> customers.normalisePhone(phone))
                    .orElseThrow(() -> InvalidRequestException.of("phone", "not a valid phone number"));
            final List<CustomerSummary> owners = tx.execute(s -> customers.findByPhone(e164)).stream()
                    .filter(CustomerSummary::active).toList();
            if (owners.isEmpty()) {
                return;
            }
            final Instant now = clock.instant();
            final String code = Credentials.digits(6);
            final boolean created = Boolean.TRUE.equals(tx.execute(s -> {
                final long last10 = count(e164, now.minus(Duration.ofMinutes(10)));
                final long lastDay = count(e164, now.minus(Duration.ofDays(1)));
                if (last10 >= PHONE_PER_10_MIN || lastDay >= PHONE_PER_DAY) {
                    meters.counter("isp.identity.otp.limited", "scope", "phone").increment();
                    return false;
                }
                // A new code replaces any earlier one.
                jdbc.sql("update identity_otp set consumed_at = :now where phone = :phone and consumed_at is null")
                        .param("now", Timestamp.from(now)).param("phone", e164).update();
                jdbc.sql("""
                                insert into identity_otp (id, tenant_id, phone, code_hash, expires_at, created_at)
                                values (:id, :tenantId, :phone, :hash, :expires, :now)
                                """)
                        .param("id", UuidV7.next(clock))
                        .param("tenantId", TenantContext.requireTenantId())
                        .param("phone", e164)
                        .param("hash", Credentials.hash(code))
                        .param("expires", Timestamp.from(now.plus(CODE_TTL)))
                        .param("now", Timestamp.from(now))
                        .update();
                return true;
            }));
            if (!created) {
                throw new TooManyRequests("Too many codes for this number. Try again later.");
            }
            final OneTimeCodes.Delivery delivery = codes.sendSignInCode(owners.getFirst().id(), e164, code);
            meters.counter("isp.identity.otp.sent", "delivery", delivery.name()).increment();
            if (delivery != OneTimeCodes.Delivery.SENT) {
                log.warn("Sign-in code for tenant {} not delivered: {}", TenantContext.requireTenantId(), delivery);
            }
            final long recent = tx.execute(s -> jdbc.sql("select count(*) from identity_otp where created_at > :since")
                    .param("since", Timestamp.from(now.minus(Duration.ofMinutes(5)))).query(Long.class).single());
            if (recent > BURST) {
                meters.counter("isp.identity.otp.bursts").increment();
                log.error("Sign-in code burst for tenant {}: {} codes in 5 minutes", TenantContext.requireTenantId(), recent);
            }
        });
        return answer;
    }

    /**
     * Checks a code. One account: a PIN_SETUP challenge. Several accounts on the phone: one challenge
     * per account, and the customer picks.
     */
    public SignIn verifyCode(final String tenantKey, final String phone, final String code) {
        final TenantRef tenant = tenants.findByCodeOrSubdomain(tenantKey).filter(TenantRef::canSignIn)
                .orElseThrow(() -> new AuthException("The code is wrong or has expired."));
        return TenantContext.callAsTenant(tenant.id(), () -> {
            final String e164 = tx.execute(s -> customers.normalisePhone(phone))
                    .orElseThrow(() -> new AuthException("The code is wrong or has expired."));
            final Instant now = clock.instant();
            final boolean ok = Boolean.TRUE.equals(tx.execute(s -> {
                final Optional<Otp> otp = jdbc.sql("""
                                select id, code_hash, attempts from identity_otp
                                where phone = :phone and consumed_at is null and expires_at > :now
                                order by created_at desc limit 1 for update
                                """)
                        .param("phone", e164).param("now", Timestamp.from(now))
                        .query((rs, n) -> new Otp(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3)))
                        .optional();
                if (otp.isEmpty()) {
                    return false;
                }
                final boolean right = code != null && Credentials.matches(code.strip(), otp.get().codeHash());
                final int attempts = otp.get().attempts() + 1;
                jdbc.sql("""
                                update identity_otp set attempts = :attempts,
                                    consumed_at = case when :done then cast(:now as timestamptz) else null end
                                where id = :id
                                """)
                        .param("attempts", attempts)
                        .param("done", right || attempts >= CODE_ATTEMPTS)
                        .param("now", Timestamp.from(now))
                        .param("id", otp.get().id())
                        .update();
                return right;
            }));
            if (!ok) {
                throw new AuthException("The code is wrong or has expired.");
            }
            final List<CustomerSummary> owners = tx.execute(s -> customers.findByPhone(e164)).stream()
                    .filter(CustomerSummary::active).toList();
            if (owners.isEmpty()) {
                throw new AuthException("The code is wrong or has expired.");
            }
            final List<SignIn.Account> accounts = owners.stream()
                    .map(c -> new SignIn.Account(c.number(), c.name(),
                            tokens.challenge(Tokens.CUSTOMER, c.id(), tenant.id(), "PIN_SETUP")))
                    .toList();
            return accounts.size() == 1
                    ? SignIn.next("PIN_SETUP", accounts.getFirst().challenge())
                    : SignIn.chooseAccount(accounts);
        });
    }

    public SignIn setPin(final String challenge, final String pin) {
        final TokenIssuer.Challenge step = tokens.verifyChallenge(challenge, "PIN_SETUP");
        if (!Credentials.acceptablePin(pin)) {
            throw InvalidRequestException.of("pin", "4 to 6 digits, not all the same and not a run like 1234");
        }
        return TenantContext.callAsTenant(step.tenantId(), () -> {
            final UUID customerId = step.subjectId();
            final Instant now = clock.instant();
            tx.executeWithoutResult(s -> {
                jdbc.sql("""
                                insert into identity_customer_credential (tenant_id, customer_id, pin_hash, pin_set_at)
                                values (:tenantId, :customerId, :hash, :now)
                                on conflict (tenant_id, customer_id) do update
                                set pin_hash = excluded.pin_hash, pin_set_at = excluded.pin_set_at, failed_logins = 0,
                                    locked_until = null
                                """)
                        .param("tenantId", TenantContext.requireTenantId())
                        .param("customerId", customerId)
                        .param("hash", Credentials.hash(pin))
                        .param("now", Timestamp.from(now))
                        .update();
                versions.bump(Tokens.CUSTOMER, step.tenantId(), customerId);
                audit.recordAs(Tokens.CUSTOMER, customerId, AuditEntry.of("identity.pin_set", "customer", customerId));
            });
            return signedIn(customerId);
        });
    }

    /** {@code account}: the customer number, or the phone when it belongs to exactly one account. */
    public SignIn login(final String tenantKey, final String account, final String pin) {
        final TenantRef tenant = tenants.findByCodeOrSubdomain(tenantKey).filter(TenantRef::canSignIn)
                .orElseThrow(() -> new AuthException(WRONG));
        return TenantContext.callAsTenant(tenant.id(), () -> {
            final CustomerSummary customer = resolve(account).orElseThrow(() -> new AuthException(WRONG));
            final Credential credential = credential(customer.id()).orElseThrow(() -> new AuthException(WRONG));
            final Instant now = clock.instant();
            if (credential.lockedUntil() != null && now.isBefore(credential.lockedUntil())) {
                throw new AuthException("Too many attempts. Try again in 15 minutes.", true);
            }
            if (credential.pinHash() == null || !Credentials.matches(pin, credential.pinHash())) {
                tx.executeWithoutResult(s -> {
                    final int failures = credential.failedLogins() + 1;
                    final boolean lock = failures >= StaffSignIn.MAX_FAILURES;
                    jdbc.sql("update identity_customer_credential set failed_logins = :failures, locked_until = :locked where customer_id = :id")
                            .param("failures", lock ? 0 : failures)
                            .param("locked", lock ? Timestamp.from(now.plus(StaffSignIn.LOCKOUT)) : null)
                            .param("id", customer.id())
                            .update();
                    if (lock) {
                        audit.recordAs(Tokens.CUSTOMER, customer.id(), new AuditEntry("identity.locked_out", "customer",
                                customer.id(), null, Map.of("minutes", StaffSignIn.LOCKOUT.toMinutes()), null));
                    }
                });
                throw new AuthException(WRONG);
            }
            return signedIn(customer.id());
        });
    }

    /** For the refresh endpoint: a fresh access token if the customer may still sign in. */
    Optional<String> reissue(final UUID tenantId, final UUID customerId) {
        return TenantContext.callAsTenant(tenantId, () -> {
            final boolean active = tx.execute(s -> customers.find(customerId)).map(CustomerSummary::active).orElse(false);
            return active ? credential(customerId).filter(c -> c.pinHash() != null).map(c -> tokens.access(subject(customerId, c)))
                    : Optional.<String>empty();
        });
    }

    private SignIn signedIn(final UUID customerId) {
        final Credential credential = credential(customerId).orElseThrow();
        tx.executeWithoutResult(s -> {
            jdbc.sql("update identity_customer_credential set failed_logins = 0, locked_until = null, last_login_at = :now where customer_id = :id")
                    .param("now", Timestamp.from(clock.instant())).param("id", customerId).update();
            audit.recordAs(Tokens.CUSTOMER, customerId, AuditEntry.of("identity.signed_in", "customer", customerId));
        });
        return SignIn.signedIn(tokens.access(subject(customerId, credential)),
                refreshTokens.issue(Tokens.CUSTOMER, customerId, TenantContext.requireTenantId()));
    }

    private static TokenIssuer.Subject subject(final UUID customerId, final Credential credential) {
        return new TokenIssuer.Subject(Tokens.CUSTOMER, customerId, TenantContext.requireTenantId(), null, null,
                credential.tokenVersion());
    }

    private Optional<CustomerSummary> resolve(final String account) {
        if (account == null || account.isBlank()) {
            return Optional.empty();
        }
        return tx.execute(s -> {
            final Optional<CustomerSummary> byNumber = customers.findByNumber(account.strip().toUpperCase(java.util.Locale.ROOT));
            if (byNumber.isPresent()) {
                return byNumber.filter(CustomerSummary::active);
            }
            final List<CustomerSummary> byPhone = customers.normalisePhone(account).map(customers::findByPhone).orElse(List.of())
                    .stream().filter(CustomerSummary::active).toList();
            return byPhone.size() == 1 ? Optional.of(byPhone.getFirst()) : Optional.<CustomerSummary>empty();
        });
    }

    private Optional<Credential> credential(final UUID customerId) {
        return tx.execute(s -> jdbc.sql("""
                        select pin_hash, failed_logins, locked_until, token_version
                        from identity_customer_credential where customer_id = :id
                        """)
                .param("id", customerId)
                .query((rs, n) -> new Credential(rs.getString(1), rs.getInt(2),
                        rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant(), rs.getInt(4)))
                .optional());
    }

    private long count(final String e164, final Instant since) {
        return jdbc.sql("select count(*) from identity_otp where phone = :phone and created_at > :since")
                .param("phone", e164).param("since", Timestamp.from(since)).query(Long.class).single();
    }

    /** Per client address, in Redis; if Redis is down the per-phone limits (database) still hold. */
    private void limitAddress(final String address) {
        if (address == null) {
            return;
        }
        try {
            final long short10 = increment("otpip:10m:" + address, Duration.ofMinutes(10));
            final long day = increment("otpip:1d:" + address, Duration.ofDays(1));
            if (short10 > IP_PER_10_MIN || day > IP_PER_DAY) {
                meters.counter("isp.identity.otp.limited", "scope", "address").increment();
                if (short10 == IP_PER_10_MIN + 1L) {
                    log.error("Sign-in code burst from one client address ({} in 10 minutes)", short10);
                    meters.counter("isp.identity.otp.bursts").increment();
                }
                throw new TooManyRequests("Too many codes requested. Try again later.");
            }
        } catch (TooManyRequests e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("Redis unavailable for sign-in code limits ({}); per-phone limits still apply", e.getClass().getSimpleName());
        }
    }

    private long increment(final String key, final Duration window) {
        final Long value = redis.opsForValue().increment(key);
        if (value != null && value == 1L) {
            redis.expire(key, window);
        }
        return value == null ? 0 : value;
    }

    private record Otp(UUID id, String codeHash, int attempts) {}

    private record Credential(String pinHash, int failedLogins, Instant lockedUntil, int tokenVersion) {}
}
