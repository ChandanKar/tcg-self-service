package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.Environment;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** Lease policy resolution and bounds (E15-T01): platform default 240, max 720, absolute 4320. */
class LeaseServiceTest {

    private static LeaseService service(boolean enabled) {
        return new LeaseService(null, null, null, null, mock(SecurityService.class), mock(AuditService.class),
                mock(UserService.class), enabled, 240, 720, 4320);
    }

    private static Environment env(Boolean leaseEnabled, Integer def, Integer max) {
        Environment env = new Environment("env-1");
        env.setLeaseEnabled(leaseEnabled);
        env.setLeaseDefaultMinutes(def);
        env.setLeaseMaxMinutes(max);
        return env;
    }

    @Test
    void leasesApplyOnlyWhenTheFeatureIsOnAndTheEnvironmentOptedIn() {
        assertThat(service(true).isEnabledFor(env(true, null, null))).isTrue();
        assertThat(service(true).isEnabledFor(env(false, null, null))).isFalse();
        assertThat(service(false).isEnabledFor(env(true, null, null))).isFalse();
    }

    @Test
    void effectiveValuesFallBackToThePlatformAndStayWithinIt() {
        LeaseService leases = service(true);
        assertThat(leases.effectiveDefault(env(true, null, null))).isEqualTo(240);
        assertThat(leases.effectiveMax(env(true, null, null))).isEqualTo(720);
        assertThat(leases.effectiveDefault(env(true, 60, 120))).isEqualTo(60);
        assertThat(leases.effectiveMax(env(true, 60, 120))).isEqualTo(120);
        // An owner maximum below the platform default caps the default too.
        assertThat(leases.effectiveDefault(env(true, null, 120))).isEqualTo(120);
        // A stored maximum above a later-lowered platform maximum is capped.
        assertThat(leases.effectiveMax(env(true, null, 900))).isEqualTo(720);
    }

    @Test
    void policyBoundsAreFifteenMinutesToThePlatformMaximum() {
        LeaseService leases = service(true);
        assertThatCode(() -> leases.validatePolicy(240, 720)).doesNotThrowAnyException();
        assertThatCode(() -> leases.validatePolicy(null, null)).doesNotThrowAnyException();
        assertThatCode(() -> leases.validatePolicy(null, 60)).doesNotThrowAnyException();
        assertThatThrownBy(() -> leases.validatePolicy(240, 900)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> leases.validatePolicy(10, 60)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> leases.validatePolicy(120, 60)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> leases.validatePolicy(null, 10)).isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(e.getMessage()).isNotBlank());
    }
}
