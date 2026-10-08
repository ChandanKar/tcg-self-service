package com.tcgdigital.vmcontrol.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ENTRAID_ENABLED=false (permit-all dev security) is refused outside the dev and test profiles.
 */
class SecurityModeGuardTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SecurityModeGuard.class);

    @Test
    void entraOffWithoutDevOrTestProfileFailsToStart() {
        runner.withPropertyValues("entraid.enabled=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("only allowed with the dev or test profile");
                });
    }

    @Test
    void entraOffWithDevProfileStarts() {
        runner.withPropertyValues("entraid.enabled=false", "spring.profiles.active=dev")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(SecurityModeGuard.class));
    }

    @Test
    void entraOnNeedsNoGuard() {
        runner.withPropertyValues("entraid.enabled=true")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(SecurityModeGuard.class));
    }
}
