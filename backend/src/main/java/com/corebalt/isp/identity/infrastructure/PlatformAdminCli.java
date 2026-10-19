package com.corebalt.isp.identity.infrastructure;

import com.corebalt.isp.identity.application.PlatformAdmins;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Break-glass reset of a platform admin (ADR 0010), run inside the backend container as
 * {@code java -jar /app/app.jar reset-platform-admin <email>} (infra/scripts/reset-platform-admin).
 * Never an HTTP endpoint. Prints the temporary password to the terminal only, never to the log, and
 * writes the platform audit log with the operator ({@code ISP_OPERATOR}, else the OS user).
 */
@Component
@ConditionalOnProperty(name = "isp.cli.reset-platform-admin")
class PlatformAdminCli implements ApplicationRunner, ExitCodeGenerator {

    private final PlatformAdmins admins;
    private final String email;
    private int exitCode = 1;

    PlatformAdminCli(final PlatformAdmins admins, @Value("${isp.cli.reset-platform-admin}") final String email) {
        this.admins = admins;
        this.email = email;
    }

    @Override
    public void run(final ApplicationArguments args) {
        final String operator = Optional.ofNullable(System.getenv("ISP_OPERATOR"))
                .orElse(System.getProperty("user.name", "unknown"));
        final Optional<String> temporary = admins.breakGlassReset(email, operator);
        if (temporary.isEmpty()) {
            System.err.println("No platform admin has the email " + email + ".");
            exitCode = 1;
            return;
        }
        System.out.println();
        System.out.println("Platform admin " + email + " was reset. Temporary password (shown once):");
        System.out.println();
        System.out.println("    " + temporary.get());
        System.out.println();
        System.out.println("Sign in with it, set a new password and enrol TOTP again. Every session was ended.");
        exitCode = 0;
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
