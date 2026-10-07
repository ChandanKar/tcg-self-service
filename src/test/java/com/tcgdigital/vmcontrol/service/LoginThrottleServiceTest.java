package com.tcgdigital.vmcontrol.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class LoginThrottleServiceTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final LoginThrottleService throttle = new LoginThrottleService(clock, 5, 20, Duration.ofMinutes(15));

    @Test
    void fiveFailuresBlockTheUsernameUntilTheOldestLeavesTheWindow() {
        for (int i = 0; i < 4; i++) {
            throttle.recordFailure("alice", "10.0.0.1");
        }
        assertThat(throttle.blockedFor("alice", "10.0.0.1")).isEmpty();

        clock.advance(Duration.ofMinutes(5));
        throttle.recordFailure("alice", "10.0.0.1");

        assertThat(throttle.blockedFor("alice", "10.0.0.2")).contains(Duration.ofMinutes(10));
        clock.advance(Duration.ofMinutes(10));
        assertThat(throttle.blockedFor("alice", "10.0.0.2")).isEmpty();
    }

    @Test
    void usernameIsCaseInsensitive() {
        for (int i = 0; i < 5; i++) {
            throttle.recordFailure("Alice", "10.0.0." + i);
        }
        assertThat(throttle.blockedFor("alice ", "10.0.0.99")).isPresent();
    }

    @Test
    void successClearsTheUsernameButNotTheIp() {
        for (int i = 0; i < 5; i++) {
            throttle.recordFailure("alice", "10.0.0.1");
        }
        throttle.recordSuccess("alice");

        assertThat(throttle.blockedFor("alice", "10.0.0.2")).isEmpty();
    }

    @Test
    void ipLimitIsIndependentOfUsername() {
        for (int i = 0; i < 20; i++) {
            throttle.recordFailure("user" + i, "10.0.0.1");
        }

        assertThat(throttle.blockedFor("someone-new", "10.0.0.1")).isPresent();
        assertThat(throttle.blockedFor("someone-new", "10.0.0.2")).isEmpty();
    }

    @Test
    void failuresExpireAfterTheWindow() {
        for (int i = 0; i < 5; i++) {
            throttle.recordFailure("alice", "10.0.0.1");
        }
        clock.advance(Duration.ofMinutes(15));

        assertThat(throttle.blockedFor("alice", "10.0.0.1")).isEmpty();
    }

    @Test
    void blankUsernameOnlyCountsAgainstTheIp() {
        throttle.recordFailure(null, "10.0.0.1");
        throttle.recordFailure(" ", "10.0.0.1");

        assertThat(throttle.blockedFor(null, "10.0.0.2")).isEmpty();
    }

    @Test
    void trackedKeysAreCapped() {
        for (int i = 0; i < LoginThrottleService.MAX_TRACKED_KEYS + 50; i++) {
            throttle.recordFailure("user" + i, "ip" + i);
        }
        for (int i = 0; i < 5; i++) {
            throttle.recordFailure("latest", "ip-latest");
        }

        assertThat(throttle.blockedFor("latest", "ip-other")).isPresent();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
