package com.corebalt.isp.tenancy.application;

import com.corebalt.isp.shared.id.UuidV7;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.shared.web.ConflictException;
import com.corebalt.isp.shared.web.InvalidRequestException;
import com.corebalt.isp.tenancy.api.TenantRegistry;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The tenant registry is platform-scoped (no row-level security), so it answers from any scope: a
 * caller without one (a sign-in, before the tenant is known) is served under a platform scope.
 */
@Service
class TenantRegistryService implements TenantRegistry {

    private static final String SELECT = "select id, code, name, subdomain, status from tenancy_tenant ";

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    TenantRegistryService(final JdbcClient jdbc, final TransactionTemplate tx, final Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public Optional<TenantRef> find(final UUID tenantId) {
        return inScope(() -> jdbc.sql(SELECT + "where id = :id").param("id", tenantId).query(TenantRegistryService::row).optional());
    }

    @Override
    public Optional<TenantRef> findByCodeOrSubdomain(final String codeOrSubdomain) {
        if (codeOrSubdomain == null || codeOrSubdomain.isBlank()) {
            return Optional.empty();
        }
        final String value = codeOrSubdomain.strip();
        return inScope(() -> jdbc.sql(SELECT + "where code = :code or subdomain = :subdomain")
                .param("code", value.toUpperCase(Locale.ROOT))
                .param("subdomain", value.toLowerCase(Locale.ROOT))
                .query(TenantRegistryService::row)
                .optional());
    }

    @Override
    public List<TenantRef> list() {
        return inScope(() -> jdbc.sql(SELECT + "order by code").query(TenantRegistryService::row).list());
    }

    @Override
    public TenantRef create(final String code, final String name, final String subdomain) {
        final String normalisedCode = code.strip().toUpperCase(Locale.ROOT);
        final String normalisedSubdomain = subdomain.strip().toLowerCase(Locale.ROOT);
        if (!normalisedCode.matches("[A-Z]{2,4}")) {
            throw InvalidRequestException.of("code", "two to four letters (T2)");
        }
        if (!normalisedSubdomain.matches("[a-z0-9][a-z0-9-]{1,40}")) {
            throw InvalidRequestException.of("subdomain", "lower-case letters, digits and hyphens");
        }
        return inScope(() -> {
            if (findByCodeOrSubdomain(normalisedCode).isPresent() || findByCodeOrSubdomain(normalisedSubdomain).isPresent()) {
                throw new ConflictException("A tenant with this code or subdomain exists.");
            }
            final UUID id = UuidV7.next(clock);
            jdbc.sql("insert into tenancy_tenant (id, code, name, subdomain, status) values (:id, :code, :name, :subdomain, 'TRIAL')")
                    .param("id", id)
                    .param("code", normalisedCode)
                    .param("name", name.strip())
                    .param("subdomain", normalisedSubdomain)
                    .update();
            return new TenantRef(id, normalisedCode, name.strip(), normalisedSubdomain, "TRIAL");
        });
    }

    private <T> T inScope(final Supplier<T> work) {
        return TenantContext.current().isPresent()
                ? tx.execute(s -> work.get())
                : TenantContext.callAsPlatform("tenant-registry", () -> tx.execute(s -> work.get()));
    }

    private static TenantRef row(final java.sql.ResultSet rs, final int row) throws java.sql.SQLException {
        return new TenantRef(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5));
    }
}
