package com.tcgdigital.vmcontrol.security;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Refuses to start with Entra ID switched off outside the dev or test profile. Without Entra the
 * app runs DefaultSecurityConfig, which permits every request and trusts an X-User-Id header;
 * that must never happen on a shared or production server by accident (M22).
 */
@Component
@ConditionalOnProperty(name = "entraid.enabled", havingValue = "false")
public class SecurityModeGuard {

    private final Environment environment;

    public SecurityModeGuard(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void checkProfile() {
        if (!environment.acceptsProfiles(Profiles.of("dev", "test"))) {
            throw new IllegalStateException("entraid.enabled=false is only allowed with the dev or test profile "
                    + "(set SPRING_PROFILES_ACTIVE=dev for local runs, or enable Entra ID)");
        }
    }
}
