package com.corebalt.isp;

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
            if (args.length != 2 || args[1].isBlank()) {
                System.err.println("usage: " + RESET_PLATFORM_ADMIN + " <email>");
                System.exit(2);
            }
            // No web server, jobs or queue listeners: the command needs only the database.
            final SpringApplication cli = new SpringApplication(IspApplication.class);
            cli.setWebApplicationType(WebApplicationType.NONE);
            cli.setDefaultProperties(Map.of(
                    "isp.cli.reset-platform-admin", args[1].strip(),
                    "isp.scheduling.enabled", "false",
                    "isp.bootstrap.enabled", "false",
                    "spring.rabbitmq.listener.simple.auto-startup", "false",
                    "spring.rabbitmq.listener.direct.auto-startup", "false",
                    "spring.main.banner-mode", "off"));
            System.exit(SpringApplication.exit(cli.run()));
        }
        SpringApplication.run(IspApplication.class, args);
    }
}
