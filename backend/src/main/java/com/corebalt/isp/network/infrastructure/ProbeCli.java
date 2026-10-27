package com.corebalt.isp.network.infrastructure;

import com.corebalt.isp.network.application.ProbeSetup;
import com.corebalt.isp.shared.web.InvalidRequestException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The platform-only probe setup (ADR 0019), run inside the backend container once per hub as
 * {@code java -jar /app/app.jar probe-setup <hub> <nas-address>} ({@code stackctl probe-setup}). Never an
 * HTTP endpoint. Prints what exists, never a password or secret.
 */
@Component
@ConditionalOnProperty(name = "isp.cli.probe-setup.hub")
class ProbeCli implements ApplicationRunner, ExitCodeGenerator {

    private final ProbeSetup setup;
    private final String hub;
    private final String nasAddress;
    private int exitCode = 1;

    ProbeCli(final ProbeSetup setup, @Value("${isp.cli.probe-setup.hub}") final String hub,
             @Value("${isp.cli.probe-setup.nas-address}") final String nasAddress) {
        this.setup = setup;
        this.hub = hub;
        this.nasAddress = nasAddress;
    }

    @Override
    public void run(final ApplicationArguments args) {
        try {
            final ProbeSetup.Result result = setup.ensure(hub, nasAddress);
            System.out.println("Probe NAS " + result.nas() + " at " + result.nasAddress()
                    + (result.nasCreated() ? " created; FreeRADIUS loads it at its next reload (routersync)." : " already there."));
            result.lines().forEach(line -> System.out.println("Probe line " + line.pppoeUsername() + ": " + line.state()
                    + ", expires " + line.expiresAt()));
            System.out.println(result.linesCreated() + " line(s) created. Passwords stay in the database (ADR 0009).");
            exitCode = 0;
        } catch (InvalidRequestException e) {
            e.violations().forEach(v -> System.err.println(v.field() + ": " + v.message()));
            exitCode = 2;
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
