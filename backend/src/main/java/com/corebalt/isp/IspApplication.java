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

    public static void main(final String[] args) {
        if (args.length > 0 && RESET_PLATFORM_ADMIN.equals(args[0])) {
            System.exit(resetPlatformAdmin(args));
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
}
