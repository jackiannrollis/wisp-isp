package com.corebalt.isp.shared.tenant;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.sql.PreparedStatement;
import java.util.UUID;
import org.hibernate.Session;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;

/**
 * Sets {@code app.tenant_id} (transaction-local) at the start of every transaction so PostgreSQL
 * row-level security sees the current tenant. A transaction with no tenant context fails (T1);
 * platform scope runs with no tenant set, so tenant-scoped tables return nothing.
 */
public class TenantAwareJpaTransactionManager extends JpaTransactionManager {

    private static final String SET_TENANT = "select set_config('app.tenant_id', ?, true)";

    public TenantAwareJpaTransactionManager(final EntityManagerFactory emf) {
        super(emf);
    }

    @Override
    protected void doBegin(final Object transaction, final TransactionDefinition definition) {
        final TenantScope scope = TenantContext.requireScope();
        super.doBegin(transaction, definition);
        if (scope instanceof TenantScope.Tenant(final UUID tenantId)) {
            final EntityManager em =
                    EntityManagerFactoryUtils.getTransactionalEntityManager(
                            obtainEntityManagerFactory());
            if (em == null) {
                throw new CannotCreateTransactionException("No transactional EntityManager bound");
            }
            try {
                em.unwrap(Session.class)
                        .doWork(
                                connection -> {
                                    try (PreparedStatement statement =
                                            connection.prepareStatement(SET_TENANT)) {
                                        statement.setString(1, tenantId.toString());
                                        statement.execute();
                                    }
                                });
            } catch (RuntimeException e) {
                throw new CannotCreateTransactionException("Could not set app.tenant_id", e);
            }
        }
    }
}
