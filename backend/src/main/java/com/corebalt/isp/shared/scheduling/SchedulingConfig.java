package com.corebalt.isp.shared.scheduling;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduled jobs run unless {@code isp.scheduling.enabled=false}: the break-glass command turns
 * them off, as it only needs the database.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "isp.scheduling.enabled", havingValue = "true", matchIfMissing = true)
@EnableScheduling
class SchedulingConfig {}
