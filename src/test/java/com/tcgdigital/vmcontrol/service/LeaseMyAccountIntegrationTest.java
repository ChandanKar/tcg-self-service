package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.LeaseDTO;
import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AccessScopeType;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** My Account lists the leases a user is still keeping alive (E15-T07). */
@TestPropertySource(properties = "lease.enabled=true")
class LeaseMyAccountIntegrationTest extends AbstractIntegrationTest {

    @Autowired private MyAccountService myAccountService;
    @Autowired private LeaseService leaseService;
    @Autowired private LockService lockService;

    private Environment leaseEnvironment(String name, User... users) {
        Environment env = newEnvironment(name);
        env.setLeaseEnabled(true);
        env = environmentRepository.saveAndFlush(env);
        for (User user : users) {
            grant(user, AccessScopeType.ENVIRONMENT, env.getEnvironmentId(), AccessLevel.USER);
        }
        return env;
    }

    @Test
    void startedAndJoinedLeasesAreListedEndingSoonestFirstButLeftAndEndedOnesAreNot() {
        User alice = newUser("alice-my-leases@example.org", false, false);
        User bob = newUser("bob-my-leases@example.org", false, false);
        Environment started = leaseEnvironment("Started", alice);
        Environment joined = leaseEnvironment("Joined", alice, bob);
        Environment left = leaseEnvironment("Left", alice);
        Environment ended = leaseEnvironment("Ended", alice);

        leaseService.openOrJoinOnStart(started.getEnvironmentId(), alice.getUserId(), 240);
        leaseService.openOrJoinOnStart(joined.getEnvironmentId(), bob.getUserId(), 60);
        leaseService.openOrJoinOnStart(joined.getEnvironmentId(), alice.getUserId(), 30);
        // Alice keeps "Left" running through a lock, then releases it: she leaves the lease.
        lockService.acquireLock(left.getEnvironmentId(), alice.getUserId(), "short", 60);
        lockService.releaseLock(left.getEnvironmentId(), alice.getUserId());
        leaseService.openOrJoinOnStart(ended.getEnvironmentId(), alice.getUserId(), 60);
        leaseService.endOnFullStop(ended.getEnvironmentId(), alice.getUserId());

        List<LeaseDTO> leases = myAccountService.getMyLeases(alice.getUserId());
        assertThat(leases).extracting(LeaseDTO::getEnvironmentName).containsExactly("Joined", "Started");
        assertThat(leases).allMatch(l -> l.getPermissions().canExtend());
        assertThat(myAccountService.getMyLeases(bob.getUserId())).extracting(LeaseDTO::getEnvironmentName)
                .containsExactly("Joined");
    }
}
