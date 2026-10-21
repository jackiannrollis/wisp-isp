package com.corebalt.isp.identity.application;

import com.corebalt.isp.shared.audit.AuditEntry;
import com.corebalt.isp.shared.audit.AuditTrail;
import com.corebalt.isp.shared.id.UuidV7;
import com.corebalt.isp.shared.security.Permissions;
import com.corebalt.isp.shared.security.Tokens;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.shared.web.ConflictException;
import com.corebalt.isp.shared.web.InvalidRequestException;
import com.corebalt.isp.shared.web.NotFoundException;
import java.sql.Array;
import java.time.Clock;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A tenant's roles: sets of the fixed permission codes (A2). Four built-in roles are created with
 * the tenant; Owner cannot be edited. A role holding a finance or admin permission
 * ({@link #NEEDS_2FA}) always requires 2FA. Changing a role's permissions bumps the token version of
 * everyone holding it, so their old tokens stop working on the next request.
 */
@Service
public class Roles {

    public static final String OWNER = "Owner";

    /** A2: holding any of these makes 2FA mandatory for the role. */
    public static final Set<String> NEEDS_2FA = Set.of(
            Permissions.STAFF_MANAGE, Permissions.SETTINGS_SECRETS, Permissions.SETTINGS_WRITE, Permissions.BILLING_ADJUST,
            Permissions.PAYMENTS_RECORD_MANUAL, Permissions.PAYMENTS_ASSIGN, Permissions.PAYMENTS_RECONCILE,
            Permissions.FINANCE_READ);

    static final Map<String, List<String>> BUILT_IN = builtIns();

    public record RoleDto(UUID id, String name, List<String> permissions, boolean requires2fa, boolean builtIn, long members) {}

    public record RoleRequest(String name, List<String> permissions) {}

    private final JdbcClient jdbc;
    private final TokenVersions versions;
    private final AuditTrail audit;
    private final Clock clock;

    Roles(final JdbcClient jdbc, final TokenVersions versions, final AuditTrail audit, final Clock clock) {
        this.jdbc = jdbc;
        this.versions = versions;
        this.audit = audit;
        this.clock = clock;
    }

    private static Map<String, List<String>> builtIns() {
        final Map<String, List<String>> roles = new LinkedHashMap<>();
        roles.put(OWNER, Permissions.ALL);
        roles.put("Finance", List.of(Permissions.PAYMENTS_RECORD_MANUAL, Permissions.PAYMENTS_ASSIGN, Permissions.PAYMENTS_STK,
                Permissions.PAYMENTS_RECONCILE, Permissions.BILLING_ADJUST, Permissions.AUDIT_READ, Permissions.FINANCE_READ));
        roles.put("Support", List.of(Permissions.CUSTOMERS_WRITE, Permissions.SUBSCRIPTIONS_WRITE, Permissions.PAYMENTS_STK));
        roles.put("Technician", List.of(Permissions.NETWORK_WRITE, Permissions.SUBSCRIPTIONS_WRITE));
        return roles;
    }

    /** Creates the built-in roles of the current tenant if missing; returns the Owner role. */
    @Transactional
    public UUID ensureBuiltIns() {
        BUILT_IN.forEach((name, permissions) -> jdbc.sql("""
                        insert into identity_role (id, tenant_id, name, permissions, requires_2fa, built_in)
                        values (:id, :tenantId, :name, :permissions, :twoFa, true)
                        on conflict (tenant_id, lower(name)) do nothing
                        """)
                .param("id", UuidV7.next(clock))
                .param("tenantId", TenantContext.requireTenantId())
                .param("name", name)
                .param("permissions", permissions.toArray(String[]::new))
                .param("twoFa", requires2fa(permissions))
                .update());
        return jdbc.sql("select id from identity_role where lower(name) = lower(:name)")
                .param("name", OWNER).query(UUID.class).single();
    }

    @Transactional
    public List<RoleDto> list() {
        ensureBuiltIns();
        return jdbc.sql("""
                        select r.id, r.name, r.permissions, r.requires_2fa, r.built_in,
                               (select count(*) from identity_staff_user s where s.role_id = r.id and s.status <> 'DISABLED')
                        from identity_role r order by r.built_in desc, r.name
                        """)
                .query((rs, n) -> new RoleDto(rs.getObject(1, UUID.class), rs.getString(2), strings(rs.getArray(3)),
                        rs.getBoolean(4), rs.getBoolean(5), rs.getLong(6)))
                .list();
    }

    @Transactional
    public RoleDto create(final RoleRequest request) {
        final List<String> permissions = validate(request);
        final UUID id = UuidV7.next(clock);
        if (exists(request.name(), null)) {
            throw new ConflictException("A role named " + request.name().strip() + " already exists.");
        }
        jdbc.sql("""
                        insert into identity_role (id, tenant_id, name, permissions, requires_2fa, built_in)
                        values (:id, :tenantId, :name, :permissions, :twoFa, false)
                        """)
                .param("id", id)
                .param("tenantId", TenantContext.requireTenantId())
                .param("name", request.name().strip())
                .param("permissions", permissions.toArray(String[]::new))
                .param("twoFa", requires2fa(permissions))
                .update();
        audit.record(AuditEntry.of("identity.role_created", "role", id, Map.of("name", request.name().strip(),
                "permissions", permissions)));
        return get(id);
    }

    @Transactional
    public RoleDto update(final UUID id, final RoleRequest request) {
        final RoleDto before = get(id);
        if (before.builtIn() && OWNER.equals(before.name())) {
            throw new ConflictException("The Owner role always has every permission and cannot be edited.");
        }
        final List<String> permissions = validate(request);
        if (exists(request.name(), id)) {
            throw new ConflictException("A role named " + request.name().strip() + " already exists.");
        }
        jdbc.sql("""
                        update identity_role set name = :name, permissions = :permissions, requires_2fa = :twoFa,
                            updated_at = now() where id = :id
                        """)
                .param("name", before.builtIn() ? before.name() : request.name().strip())
                .param("permissions", permissions.toArray(String[]::new))
                .param("twoFa", requires2fa(permissions))
                .param("id", id)
                .update();
        jdbc.sql("select id from identity_staff_user where role_id = :id").param("id", id).query(UUID.class).list()
                .forEach(staff -> versions.bump(Tokens.STAFF, TenantContext.requireTenantId(), staff));
        audit.record(new AuditEntry("identity.role_changed", "role", id,
                Map.of("name", before.name(), "permissions", before.permissions()),
                Map.of("name", request.name().strip(), "permissions", permissions), null));
        return get(id);
    }

    @Transactional
    public RoleDto get(final UUID id) {
        return jdbc.sql("""
                        select r.id, r.name, r.permissions, r.requires_2fa, r.built_in,
                               (select count(*) from identity_staff_user s where s.role_id = r.id and s.status <> 'DISABLED')
                        from identity_role r where r.id = :id
                        """)
                .param("id", id)
                .query((rs, n) -> new RoleDto(rs.getObject(1, UUID.class), rs.getString(2), strings(rs.getArray(3)),
                        rs.getBoolean(4), rs.getBoolean(5), rs.getLong(6)))
                .optional()
                .orElseThrow(() -> new NotFoundException("role"));
    }

    static boolean requires2fa(final List<String> permissions) {
        return permissions.stream().anyMatch(NEEDS_2FA::contains);
    }

    private boolean exists(final String name, final UUID except) {
        return jdbc.sql("select count(*) from identity_role where lower(name) = lower(:name) and id is distinct from :except")
                .param("name", name.strip()).param("except", except).query(Long.class).single() > 0;
    }

    private static List<String> validate(final RoleRequest request) {
        if (request.name() == null || request.name().isBlank() || request.name().strip().length() > 60) {
            throw InvalidRequestException.of("name", "1 to 60 characters");
        }
        final List<String> permissions = request.permissions() == null ? List.of() : request.permissions().stream().distinct().toList();
        final List<String> unknown = permissions.stream().filter(p -> !Permissions.ALL.contains(p)).toList();
        if (!unknown.isEmpty()) {
            throw InvalidRequestException.of("permissions", "unknown codes: " + String.join(", ", unknown));
        }
        // Only the built-in Owner role, which is never edited here, holds the account's own settings.
        if (permissions.contains(Permissions.SETTINGS_WRITE)) {
            throw InvalidRequestException.of("permissions", Permissions.SETTINGS_WRITE + " is held by the Owner role only");
        }
        return Permissions.ALL.stream().filter(permissions::contains).toList();
    }

    static List<String> strings(final Array array) throws java.sql.SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }
}
