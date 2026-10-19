package com.corebalt.isp.support;

import com.corebalt.isp.shared.id.UuidV7;
import java.util.UUID;

/**
 * ACTIVE staff rows for tests, written as the owner. The password hash is a placeholder no
 * password matches; tests that sign in set a real one.
 */
public final class TestStaff {

    private TestStaff() {}

    public static UUID create(final UUID tenantId) {
        final UUID id = UuidV7.next();
        OwnerSql.client().sql("""
                        insert into identity_staff_user
                            (id, tenant_id, email, display_name, password_hash, status, must_change_password)
                        values (:id, :tenantId, :email, 'Test staff', 'not-a-hash', 'ACTIVE', false)
                        """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("email", id + "@staff.example.test")
                .update();
        return id;
    }
}
