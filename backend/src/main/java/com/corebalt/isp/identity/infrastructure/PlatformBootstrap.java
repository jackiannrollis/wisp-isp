package com.corebalt.isp.identity.infrastructure;

import com.corebalt.isp.identity.application.PlatformAdmins;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Creates the first platform admin from {@code ISP_BOOTSTRAP_ADMIN_EMAIL} and
 * {@code ISP_BOOTSTRAP_ADMIN_PASSWORD} at startup, only while no platform admin exists (ADR 0010).
 */
@Component
@ConditionalOnProperty(name = "isp.bootstrap.enabled", havingValue = "true", matchIfMissing = true)
class PlatformBootstrap implements ApplicationRunner {

    private final PlatformAdmins admins;
    private final String email;
    private final String password;

    PlatformBootstrap(
            final PlatformAdmins admins,
            @Value("${isp.bootstrap.admin-email:}") final String email,
            @Value("${isp.bootstrap.admin-password:}") final String password) {
        this.admins = admins;
        this.email = email;
        this.password = password;
    }

    @Override
    public void run(final ApplicationArguments args) {
        admins.bootstrap(email, password);
    }
}
