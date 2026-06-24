package com.tcgdigital.vmcontrol.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

// No longer needed — application runs on MySQL only. Kept as an empty class to avoid
// breaking the spring EnvironmentPostProcessor registration file during cleanup.
public class H2MysqlCompatibilityPostProcessor implements EnvironmentPostProcessor, Ordered {

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        // no-op
    }
}