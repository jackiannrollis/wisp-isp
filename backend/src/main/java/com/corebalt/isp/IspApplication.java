package com.corebalt.isp;

import java.util.Arrays;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class IspApplication {

    /** Break-glass command (ADR 0010), run inside the container; handled by identity's PlatformAdminCli. */
    static final String RESET_PLATFORM_ADMIN = "reset-platform-admin";

    /** The synthetic login probe's setup (ADR 0019), run inside the container; handled by network's ProbeCli. */
    static final String PROBE_SETUP = "probe-setup";

    public static void main(final String[] args) {
        if (args.length > 0 && RESET_PLATFORM_ADMIN.equals(args[0])) {
            System.exit(resetPlatformAdmin(args));
        }
        if (args.length > 0 && PROBE_SETUP.equals(args[0])) {
            System.exit(probeSetup(args));
        }
        SpringApplication.run(IspApplication.class, args);
    }

    /**
     * {@code reset-platform-admin <email> [--property=value ...]}: starts without a web server, jobs or
     * queue listeners (it needs only the database) and returns the exit code.
     */
    static int resetPlatformAdmin(final String[] args) {
        if (args.length < 2 || args[1].isBlank() || args[1].startsWith("--")) {
            System.err.println("usage: " + RESET_PLATFORM_ADMIN + " <email>");
            return 2;
        }
        final SpringApplication cli = new SpringApplication(IspApplication.class);
        cli.setWebApplicationType(WebApplicationType.NONE);
        cli.setDefaultProperties(Map.of(
                "isp.cli.reset-platform-admin", args[1].strip(),
                "isp.scheduling.enabled", "false",
                "isp.bootstrap.enabled", "false",
                "spring.rabbitmq.listener.simple.auto-startup", "false",
                "spring.rabbitmq.listener.direct.auto-startup", "false",
                "spring.main.banner-mode", "off"));
        return SpringApplication.exit(cli.run(Arrays.copyOfRange(args, 2, args.length)));
    }

    /** {@code probe-setup <hub> <nas-address> [--property=value ...]}: like {@link #resetPlatformAdmin}. */
    static int probeSetup(final String[] args) {
        if (args.length < 3 || args[1].startsWith("--") || args[2].startsWith("--")) {
            System.err.println("usage: " + PROBE_SETUP + " <hub, e.g. A> <nas-address, the worker's probe address>");
            return 2;
        }
        final SpringApplication cli = new SpringApplication(IspApplication.class);
        cli.setWebApplicationType(WebApplicationType.NONE);
        cli.setDefaultProperties(Map.of(
                "isp.cli.probe-setup.hub", args[1].strip(),
                "isp.cli.probe-setup.nas-address", args[2].strip(),
                "isp.scheduling.enabled", "false",
                "isp.bootstrap.enabled", "false",
                "spring.rabbitmq.listener.simple.auto-startup", "false",
                "spring.rabbitmq.listener.direct.auto-startup", "false",
                "spring.main.banner-mode", "off"));
        return SpringApplication.exit(cli.run(Arrays.copyOfRange(args, 3, args.length)));
    }
}
