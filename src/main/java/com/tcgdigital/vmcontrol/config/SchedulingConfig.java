package com.tcgdigital.vmcontrol.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on {@code @Scheduled} jobs. {@code app.scheduling.enabled=false} is a master switch that
 * stops every scheduled job, including those with no switch of their own
 * ({@code AccessExpirationScheduler}, {@code EksSyncScheduler}). Tests set it to false.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
