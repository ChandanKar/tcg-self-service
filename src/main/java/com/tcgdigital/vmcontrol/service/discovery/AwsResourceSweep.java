package com.tcgdigital.vmcontrol.service.discovery;

import com.tcgdigital.vmcontrol.service.CloudTarget;
import com.tcgdigital.vmcontrol.service.aws.AwsAccountService;
import com.tcgdigital.vmcontrol.service.aws.AwsRegionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.awscore.exception.AwsServiceException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * The discovery engine (U19-T02): one scan (list the EC2 instances, the EKS clusters, ...) run in
 * every account and enabled region at once (U19-T01's scan targets), a few regions at a time. A
 * throttled call waits and is tried again, so throttling slows the sweep down instead of failing it;
 * a region that refuses access is recorded and skipped for a day; one failing region or account
 * never stops the others, and a failed region is reported as failed, never as "nothing there" (H24).
 * The last outcome of each account, region and scan kind is kept for System health.
 */
@Service
public class AwsResourceSweep {

    private static final Logger log = LoggerFactory.getLogger(AwsResourceSweep.class);

    /** AWS error codes that mean "too many calls right now". */
    private static final Set<String> THROTTLING = Set.of("Throttling", "ThrottlingException", "RequestLimitExceeded",
            "TooManyRequestsException", "RequestThrottled", "RequestThrottledException", "SlowDown");

    /** What one region of one sweep found, or why it found nothing. */
    public record RegionOutcome(String kind, CloudTarget target, Instant scannedAt, int found, String error, boolean refused) {
        public boolean ok() {
            return error == null;
        }
    }

    /**
     * A sweep's results: what each target found (only the targets that answered) and every target's
     * outcome. A target missing from {@code found} failed: callers must not treat it as empty.
     */
    public record Result<T>(Map<CloudTarget, List<T>> found, List<RegionOutcome> outcomes) {
        public List<T> all() {
            List<T> out = new ArrayList<>();
            found.values().forEach(out::addAll);
            return out;
        }

        public boolean answered(CloudTarget target) {
            return found.containsKey(target);
        }

        public List<RegionOutcome> failures() {
            return outcomes.stream().filter(o -> !o.ok()).toList();
        }
    }

    private final AwsAccountService accounts;
    private final Map<String, RegionOutcome> lastOutcomes = new ConcurrentHashMap<>();
    private Clock clock = Clock.systemUTC();
    private Sleeper sleeper = millis -> Thread.sleep(millis);

    @Value("${aws.sweep.parallelism:8}")
    private int parallelism = 8;

    @Value("${aws.sweep.max-attempts:5}")
    private int maxAttempts = 5;

    @Value("${aws.sweep.first-backoff-ms:500}")
    private long firstBackoffMs = 500;

    /** How long the whole sweep may take before the regions still running are reported as timed out. */
    @Value("${aws.sweep.timeout-seconds:300}")
    private long timeoutSeconds = 300;

    /** Narrows a sweep to the regions that hold the resource type, when Resource Explorer can tell; absent in plain tests. */
    private ResourceExplorerSearch explorer;

    @Autowired
    public AwsResourceSweep(AwsAccountService accounts) {
        this.accounts = accounts;
    }

    @Autowired(required = false)
    void setExplorer(ResourceExplorerSearch explorer) {
        this.explorer = explorer;
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    /** How the back-off waits (tests: no real sleep). */
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    void setSleeper(Sleeper sleeper) {
        this.sleeper = sleeper;
    }

    void setLimits(int parallelism, int maxAttempts, long firstBackoffMs) {
        this.parallelism = parallelism;
        this.maxAttempts = maxAttempts;
        this.firstBackoffMs = firstBackoffMs;
    }

    /**
     * Runs {@code scan} in every scan target: every active account in every enabled region, narrowed
     * by {@code limit} when set. With {@code resourceType} (an AWS Resource Explorer type such as
     * "ec2:instance"), regions known to hold none of that type are skipped.
     */
    public <T> Result<T> everywhere(String kind, List<String> limit, String resourceType, Function<CloudTarget, List<T>> scan) {
        List<CloudTarget> targets = accounts.scanTargets(limit == null ? List.of() : limit);
        if (explorer != null && resourceType != null) {
            targets = explorer.narrow(targets, resourceType);
        }
        return run(kind, targets, scan);
    }

    /** Runs {@code scan} in the given targets, a few at a time, with back-off on throttling. */
    public <T> Result<T> run(String kind, Collection<CloudTarget> targets, Function<CloudTarget, List<T>> scan) {
        List<CloudTarget> list = targets.stream().distinct().toList();
        Map<CloudTarget, List<T>> found = new LinkedHashMap<>();
        List<RegionOutcome> outcomes = new ArrayList<>();
        if (list.isEmpty()) {
            return new Result<>(found, outcomes);
        }
        Instant started = clock.instant();
        AtomicInteger counter = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(parallelism, list.size())), r -> {
            Thread t = new Thread(r, "aws-sweep-" + kind + "-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        try {
            Map<CloudTarget, Future<List<T>>> futures = new LinkedHashMap<>();
            for (CloudTarget target : list) {
                futures.put(target, pool.submit(() -> withBackoff(kind, target, scan)));
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            for (Map.Entry<CloudTarget, Future<List<T>>> entry : futures.entrySet()) {
                CloudTarget target = entry.getKey();
                RegionOutcome outcome;
                try {
                    long left = Math.max(1, deadline - System.nanoTime());
                    List<T> items = entry.getValue().get(left, TimeUnit.NANOSECONDS);
                    List<T> safe = items == null ? List.of() : items;
                    found.put(target, safe);
                    outcome = new RegionOutcome(kind, target, clock.instant(), safe.size(), null, false);
                } catch (TimeoutException e) {
                    entry.getValue().cancel(true);
                    outcome = new RegionOutcome(kind, target, clock.instant(), 0, "Timed out", false);
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    boolean refused = AwsRegionService.isRefusal(cause);
                    accounts.reportScanFailure(target, cause);
                    outcome = new RegionOutcome(kind, target, clock.instant(), 0, message(cause), refused);
                    log.warn("{} in {} failed{}: {}", kind, target, refused ? " (access refused; skipped for a day)" : "", message(cause));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    outcome = new RegionOutcome(kind, target, clock.instant(), 0, "Interrupted", false);
                }
                outcomes.add(outcome);
                lastOutcomes.put(kind + "|" + target.key(), outcome);
            }
        } finally {
            pool.shutdownNow();
        }
        long failed = outcomes.stream().filter(o -> !o.ok()).count();
        log.info("{}: {} account/region target(s) in {} ms, {} found, {} failed", kind, list.size(),
                Duration.between(started, clock.instant()).toMillis(), found.values().stream().mapToInt(List::size).sum(), failed);
        return new Result<>(found, outcomes);
    }

    /** The newest outcome of every account, region and scan kind (System health, U19-T08). */
    public List<RegionOutcome> lastOutcomes() {
        return lastOutcomes.values().stream()
                .sorted((a, b) -> a.target().key().equals(b.target().key()) ? a.kind().compareTo(b.kind())
                        : a.target().key().compareTo(b.target().key()))
                .toList();
    }

    private <T> List<T> withBackoff(String kind, CloudTarget target, Function<CloudTarget, List<T>> scan) throws InterruptedException {
        long wait = firstBackoffMs;
        for (int attempt = 1; ; attempt++) {
            try {
                return scan.apply(target);
            } catch (RuntimeException e) {
                if (!isThrottling(e) || attempt >= maxAttempts) {
                    throw e;
                }
                long jitter = wait <= 1 ? 0 : ThreadLocalRandom.current().nextLong(wait / 2 + 1);
                log.debug("{} in {} throttled (attempt {}); waiting {} ms", kind, target, attempt, wait + jitter);
                sleeper.sleep(wait + jitter);
                wait = Math.min(wait * 2, 20_000);
            }
        }
    }

    /** True when the failure (or a cause) is AWS asking to slow down. */
    static boolean isThrottling(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof AwsServiceException aws && (aws.isThrottlingException()
                    || aws.awsErrorDetails() != null && THROTTLING.contains(aws.awsErrorDetails().errorCode()))) {
                return true;
            }
        }
        return false;
    }

    private static String message(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root && root.getMessage() != null && root.getMessage().startsWith("EC2 discovery failed")) {
            root = root.getCause();
        }
        String text = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
        return text.length() > 300 ? text.substring(0, 300) : text;
    }
}
