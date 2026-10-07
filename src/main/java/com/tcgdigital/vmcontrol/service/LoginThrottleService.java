package com.tcgdigital.vmcontrol.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limits failed password logins per username and per client IP within a sliding window.
 *
 * <p>Counters live in this JVM only. That is enough for one app instance; several instances
 * behind a load balancer would each allow the full limit.
 */
@Service
public class LoginThrottleService {

    static final int MAX_TRACKED_KEYS = 10_000;

    private final Clock clock;
    private final int maxFailuresPerUser;
    private final int maxFailuresPerIp;
    private final Duration window;

    private final Map<String, Deque<Instant>> failuresByUser = new ConcurrentHashMap<>();
    private final Map<String, Deque<Instant>> failuresByIp = new ConcurrentHashMap<>();

    @Autowired
    public LoginThrottleService(@Value("${auth.login.max-failures-per-user:5}") int maxFailuresPerUser,
                                @Value("${auth.login.max-failures-per-ip:20}") int maxFailuresPerIp,
                                @Value("${auth.login.window-minutes:15}") long windowMinutes) {
        this(Clock.systemUTC(), maxFailuresPerUser, maxFailuresPerIp, Duration.ofMinutes(windowMinutes));
    }

    LoginThrottleService(Clock clock, int maxFailuresPerUser, int maxFailuresPerIp, Duration window) {
        this.clock = clock;
        this.maxFailuresPerUser = maxFailuresPerUser;
        this.maxFailuresPerIp = maxFailuresPerIp;
        this.window = window;
    }

    /**
     * How long the caller must wait before trying again, or empty when a login attempt is allowed.
     */
    public Optional<Duration> blockedFor(String username, String ip) {
        Instant now = clock.instant();
        Optional<Duration> byUser = blockedFor(failuresByUser, userKey(username), maxFailuresPerUser, now);
        Optional<Duration> byIp = blockedFor(failuresByIp, ip, maxFailuresPerIp, now);
        if (byUser.isPresent() && byIp.isPresent()) {
            return Optional.of(byUser.get().compareTo(byIp.get()) >= 0 ? byUser.get() : byIp.get());
        }
        return byUser.isPresent() ? byUser : byIp;
    }

    public void recordFailure(String username, String ip) {
        Instant now = clock.instant();
        record(failuresByUser, userKey(username), now);
        record(failuresByIp, ip, now);
    }

    /** A successful login clears the username's failures; the IP counter keeps running. */
    public void recordSuccess(String username) {
        String key = userKey(username);
        if (key != null) {
            failuresByUser.remove(key);
        }
    }

    private Optional<Duration> blockedFor(Map<String, Deque<Instant>> failures, String key, int limit, Instant now) {
        if (key == null) {
            return Optional.empty();
        }
        Deque<Instant> attempts = failures.get(key);
        if (attempts == null) {
            return Optional.empty();
        }
        synchronized (attempts) {
            prune(attempts, now);
            if (attempts.size() < limit) {
                return Optional.empty();
            }
            // Blocked until enough of the oldest failures leave the window to drop below the limit.
            Instant unblockAt = attempts.stream().skip(attempts.size() - limit).findFirst().orElseThrow().plus(window);
            Duration wait = Duration.between(now, unblockAt);
            return Optional.of(wait.isNegative() || wait.isZero() ? Duration.ofSeconds(1) : wait);
        }
    }

    private void record(Map<String, Deque<Instant>> failures, String key, Instant now) {
        if (key == null) {
            return;
        }
        if (failures.size() >= MAX_TRACKED_KEYS && !failures.containsKey(key)) {
            evictOldest(failures);
        }
        Deque<Instant> attempts = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (attempts) {
            prune(attempts, now);
            attempts.addLast(now);
        }
    }

    private void prune(Deque<Instant> attempts, Instant now) {
        Instant cutoff = now.minus(window);
        while (!attempts.isEmpty() && !attempts.peekFirst().isAfter(cutoff)) {
            attempts.pollFirst();
        }
    }

    private void evictOldest(Map<String, Deque<Instant>> failures) {
        String oldestKey = null;
        Instant oldest = null;
        for (Map.Entry<String, Deque<Instant>> entry : failures.entrySet()) {
            Instant last;
            synchronized (entry.getValue()) {
                last = entry.getValue().peekLast();
            }
            if (last == null || oldest == null || last.isBefore(oldest)) {
                oldestKey = entry.getKey();
                oldest = last;
                if (last == null) {
                    break;
                }
            }
        }
        if (oldestKey != null) {
            failures.remove(oldestKey);
        }
    }

    private static String userKey(String username) {
        return username == null || username.isBlank() ? null : username.trim().toLowerCase(Locale.ROOT);
    }
}
