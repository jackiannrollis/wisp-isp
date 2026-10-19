package com.corebalt.isp.support;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * SQL as the database owner (the container superuser), outside row-level security: for test setup
 * and for reading what FreeRADIUS would read from the views. Application code never has this.
 */
public final class OwnerSql {

    private OwnerSql() {}

    public static JdbcClient client() {
        return JdbcClient.create(
                new DriverManagerDataSource(
                        IntegrationTest.POSTGRES.getJdbcUrl(),
                        IntegrationTest.POSTGRES.getUsername(),
                        IntegrationTest.POSTGRES.getPassword()));
    }
}
