package com.tcgdigital.vmcontrol.service.aws;

import com.tcgdigital.vmcontrol.model.AwsAccountRegion;
import com.tcgdigital.vmcontrol.repository.AwsAccountRegionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeRegionsRequest;
import software.amazon.awssdk.services.ec2.model.Region;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * The regions each AWS account has enabled (U19-T01). ec2:DescribeRegions (opt-in regions the account
 * turned on included) is read with the account's own credentials and kept in aws_account_region for a
 * day, so a region enabled today is scanned tomorrow without a setting change. A failed read keeps what
 * was read before and is not tried again for an hour. A region whose calls were refused (an SCP, a
 * missing permission) is recorded and skipped for a day instead of being called on every run.
 */
@Service
public class AwsRegionService {

    private static final Logger log = LoggerFactory.getLogger(AwsRegionService.class);

    /** How long a read of the enabled regions is used before it is read again. */
    static final Duration MAX_AGE = Duration.ofDays(1);
    /** How long a region that refused the app's calls is left out. */
    static final Duration REFUSAL_BACKOFF = Duration.ofDays(1);
    /** How long after a failed read the next one is tried. */
    static final Duration RETRY_AFTER_FAILURE = Duration.ofHours(1);
    private static final int MAX_REASON = 500;

    /** AWS error codes that mean "not allowed here", not "something broke". */
    private static final Set<String> REFUSALS = Set.of("AuthFailure", "UnauthorizedOperation", "AccessDenied",
            "AccessDeniedException", "OptInRequired", "UnrecognizedClientException", "InvalidClientTokenId",
            "UnauthorizedException", "SignatureDoesNotMatch");

    /** One region of an account as the AWS accounts page shows it. */
    public record RegionView(String region, String optInStatus, Instant readAt, Instant refusedAt, String refusedReason) {
        public boolean refused() {
            return refusedAt != null;
        }
    }

    private final AwsAccountRegionRepository repository;
    private final BiFunction<String, String, Ec2Client> clients;
    private final Supplier<String> homeRegion;
    private final java.util.function.BooleanSupplier configured;
    private final Map<String, Instant> failedAt = new ConcurrentHashMap<>();
    private Clock clock = Clock.systemUTC();

    @Autowired
    public AwsRegionService(AwsAccountRegionRepository repository, AwsClientFactory clientFactory) {
        this(repository, clientFactory::ec2, clientFactory::defaultRegion, clientFactory::isConfigured);
    }

    /** With given clients (tests). */
    AwsRegionService(AwsAccountRegionRepository repository, BiFunction<String, String, Ec2Client> clients,
                     Supplier<String> homeRegion, java.util.function.BooleanSupplier configured) {
        this.repository = repository;
        this.clients = clients;
        this.homeRegion = homeRegion;
        this.configured = configured;
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * The account's enabled regions: the stored read while it is under a day old, else a fresh read.
     * Empty when they were never read and cannot be read now (no AWS access yet): callers then fall back
     * to the configured regions.
     */
    public Optional<List<String>> enabledRegions(String accountRef) {
        List<AwsAccountRegion> stored = repository.findByAwsAccountRefOrderByRegionCodeAsc(accountRef);
        List<String> known = codes(stored);
        Instant now = clock.instant();
        if (!known.isEmpty() && !isStale(stored, now)) {
            return Optional.of(known);
        }
        Instant failed = failedAt.get(accountRef);
        if (configured.getAsBoolean() && (failed == null || failed.plus(RETRY_AFTER_FAILURE).isBefore(now))) {
            try {
                return Optional.of(refresh(accountRef));
            } catch (Exception e) {
                failedAt.put(accountRef, now);
                log.warn("Could not read the enabled regions of account {}; {}: {}", accountRef,
                        known.isEmpty() ? "using the configured regions" : "keeping the last read", e.getMessage());
            }
        }
        return known.isEmpty() ? Optional.empty() : Optional.of(known);
    }

    /**
     * Reads the account's enabled regions from AWS now and stores them: new regions are added, regions
     * no longer enabled are removed, and a region's recorded refusal is kept. Throws when AWS cannot be
     * read, leaving the stored regions as they were.
     */
    public List<String> refresh(String accountRef) {
        List<Region> regions = clients.apply(accountRef, homeRegion.get())
                .describeRegions(DescribeRegionsRequest.builder().allRegions(false).build()).regions();
        Timestamp now = Timestamp.from(clock.instant());
        Map<String, AwsAccountRegion> stored = new LinkedHashMap<>();
        repository.findByAwsAccountRefOrderByRegionCodeAsc(accountRef).forEach(r -> stored.put(r.getRegionCode(), r));
        List<AwsAccountRegion> save = new ArrayList<>();
        List<String> codes = new ArrayList<>();
        for (Region region : regions) {
            String code = region.regionName();
            if (code == null || code.isBlank() || "not-opted-in".equals(region.optInStatus())) {
                continue;
            }
            AwsAccountRegion row = stored.remove(code);
            if (row == null) {
                row = new AwsAccountRegion(accountRef, code);
            }
            row.setOptInStatus(region.optInStatus() == null ? "opt-in-not-required" : region.optInStatus());
            row.setReadAt(now);
            save.add(row);
            codes.add(code);
        }
        repository.saveAll(save);
        if (!stored.isEmpty()) {
            repository.deleteAll(stored.values());
            log.info("Account {}: regions no longer enabled: {}", accountRef, stored.keySet());
        }
        failedAt.remove(accountRef);
        codes.sort(String::compareTo);
        log.info("Account {}: {} enabled region(s) read from AWS", accountRef, codes.size());
        return codes;
    }

    /** Records that the account's calls in a region were refused; the region is skipped for a day. */
    public void recordRefusal(String accountRef, String region, String reason) {
        if (region == null || region.isBlank()) {
            return;
        }
        Instant now = clock.instant();
        AwsAccountRegion row = repository.findByAwsAccountRefAndRegionCode(accountRef, region).orElseGet(() -> {
            AwsAccountRegion r = new AwsAccountRegion(accountRef, region);
            r.setReadAt(Timestamp.from(now));
            return r;
        });
        boolean first = row.getRefusedAt() == null;
        row.setRefusedAt(Timestamp.from(now));
        String text = reason == null ? "Access refused" : reason;
        row.setRefusedReason(text.length() > MAX_REASON ? text.substring(0, MAX_REASON) : text);
        repository.save(row);
        if (first) {
            log.warn("Account {}: calls in {} were refused; skipping the region for a day: {}", accountRef, region, text);
        }
    }

    /** The regions of the account whose calls were refused in the last day. */
    public Set<String> refusedRegions(String accountRef) {
        Instant cutoff = clock.instant().minus(REFUSAL_BACKOFF);
        Set<String> refused = new java.util.TreeSet<>();
        for (AwsAccountRegion r : repository.findByAwsAccountRefOrderByRegionCodeAsc(accountRef)) {
            if (r.getRefusedAt() != null && r.getRefusedAt().toInstant().isAfter(cutoff)) {
                refused.add(r.getRegionCode());
            }
        }
        return refused;
    }

    /** What the AWS accounts page shows: the stored regions, a refusal only while it still counts. */
    public List<RegionView> view(String accountRef) {
        Instant cutoff = clock.instant().minus(REFUSAL_BACKOFF);
        return repository.findByAwsAccountRefOrderByRegionCodeAsc(accountRef).stream().map(r -> {
            boolean refused = r.getRefusedAt() != null && r.getRefusedAt().toInstant().isAfter(cutoff);
            return new RegionView(r.getRegionCode(), r.getOptInStatus(), r.getReadAt() == null ? null : r.getReadAt().toInstant(),
                    refused ? r.getRefusedAt().toInstant() : null, refused ? r.getRefusedReason() : null);
        }).toList();
    }

    /** True when the failure says the call was not allowed (permissions, an SCP, a region not opted in). */
    public static boolean isRefusal(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof AwsServiceException aws && aws.awsErrorDetails() != null
                    && REFUSALS.contains(aws.awsErrorDetails().errorCode())) {
                return true;
            }
        }
        return false;
    }

    /** True when the oldest region read from AWS is over a day old. */
    private static boolean isStale(Collection<AwsAccountRegion> stored, Instant now) {
        return stored.stream().filter(r -> r.getOptInStatus() != null).map(r -> r.getReadAt().toInstant())
                .min(Instant::compareTo).map(oldest -> oldest.plus(MAX_AGE).isBefore(now)).orElse(true);
    }

    /** The regions read from DescribeRegions (a row only made by a refusal has no opt-in status). */
    private static List<String> codes(List<AwsAccountRegion> stored) {
        return stored.stream().filter(r -> r.getOptInStatus() != null).map(AwsAccountRegion::getRegionCode).toList();
    }
}
