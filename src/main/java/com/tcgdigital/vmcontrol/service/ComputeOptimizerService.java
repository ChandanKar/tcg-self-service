package com.tcgdigital.vmcontrol.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.computeoptimizer.ComputeOptimizerClient;
import software.amazon.awssdk.services.computeoptimizer.model.ComputeOptimizerException;
import software.amazon.awssdk.services.computeoptimizer.model.GetEc2InstanceRecommendationsRequest;
import software.amazon.awssdk.services.computeoptimizer.model.GetEc2InstanceRecommendationsResponse;
import software.amazon.awssdk.services.computeoptimizer.model.InstanceRecommendation;
import software.amazon.awssdk.services.computeoptimizer.model.InstanceRecommendationOption;
import software.amazon.awssdk.services.computeoptimizer.model.OptInRequiredException;

import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Overlays AWS Compute Optimizer's real, ML-driven EC2 rightsizing recommendations onto the
 * home-grown CPU-threshold rule in {@link CostEstimationService} — a second signal, never a
 * replacement: Compute Optimizer needs the account opted in and ~14 days of data before it
 * returns anything, and has no recommendation type for EKS managed node groups at all, so
 * {@code AWS_EKS} VMs always stay on the CPU-threshold rule regardless of this service.
 *
 * Enabled by default (E08-T10; {@code cost.optimizer.enabled}): read-only, not billed per
 * request, cached per region, and skipped without AWS credentials. An account not opted in
 * just falls back to the CPU-threshold rule.
 */
@Service
public class ComputeOptimizerService {

    private static final Logger log = LoggerFactory.getLogger(ComputeOptimizerService.class);

    @Value("${aws.access-key:}")
    private String accessKey;

    @Value("${aws.secret-key:}")
    private String secretKey;

    @Value("${cost.optimizer.enabled:true}")
    private boolean enabled;

    private final Map<String, ComputeOptimizerClient> clientCache = new ConcurrentHashMap<>();

    /** How long a region's recommendations are reused (E08-T04); failures are never cached. */
    @Value("${cost.optimizer.cache-minutes:60}")
    private long cacheMinutes = 60;

    private record CachedRecs(java.time.Instant fetchedAt, Map<String, Recommendation> recommendations) {
    }

    private final Map<String, CachedRecs> recommendationCache = new ConcurrentHashMap<>();
    /** Builds a region's client; replaceable in tests. */
    private java.util.function.Function<String, ComputeOptimizerClient> clientFactory = this::buildClient;
    private java.time.Clock clock = java.time.Clock.systemUTC();

    public ComputeOptimizerService() {
    }

    /** Test seam: fixed clients and clock. */
    ComputeOptimizerService(java.util.function.Function<String, ComputeOptimizerClient> clientFactory, java.time.Clock clock) {
        this.clientFactory = clientFactory;
        this.clock = clock;
    }

    /**
     * An OVERPROVISIONED instance and its recommended types, best rank first (E08-T05). Callers
     * still check each option is actually cheaper before suggesting it.
     */
    public record Recommendation(String instanceId, String finding, List<String> optionTypesByRank) {
        /** The top-ranked option. */
        public String suggestedInstanceType() {
            return optionTypesByRank.isEmpty() ? null : optionTypesByRank.get(0);
        }
    }

    public boolean isAvailable() {
        return enabled && accessKey != null && !accessKey.isEmpty()
                && secretKey != null && !secretKey.isEmpty();
    }

    /**
     * @return every EC2 instance in this region Compute Optimizer currently has an opinion on,
     *         keyed by instance ID — never partial/per-instance calls, one call covers the
     *         account's whole recommendation set for the region.
     */
    public Map<String, Recommendation> getEc2Recommendations(String region) {
        if (!isAvailable()) {
            return Map.of();
        }
        CachedRecs cached = recommendationCache.get(region);
        if (cached != null && cached.fetchedAt().plus(java.time.Duration.ofMinutes(cacheMinutes)).isAfter(clock.instant())) {
            return cached.recommendations();
        }
        try {
            // Every page (E08-T04): one response covers at most a page of the region's instances.
            Map<String, Recommendation> all = new LinkedHashMap<>();
            String token = null;
            do {
                GetEc2InstanceRecommendationsResponse response = getClient(region)
                        .getEC2InstanceRecommendations(GetEc2InstanceRecommendationsRequest.builder().nextToken(token).build());
                all.putAll(parseRecommendations(response));
                token = response.nextToken();
            } while (token != null && !token.isEmpty());
            Map<String, Recommendation> result = java.util.Collections.unmodifiableMap(all);
            recommendationCache.put(region, new CachedRecs(clock.instant(), result));
            return result;
        } catch (OptInRequiredException e) {
            log.warn("Compute Optimizer is not enrolled for this account (region {}) — enable it in the " +
                    "AWS console to get real rightsizing recommendations; falling back to the CPU-threshold rule.", region);
            return Map.of();
        } catch (ComputeOptimizerException e) {
            log.error("Compute Optimizer call failed for region {}: {}", region, e.getMessage());
            return Map.of();
        } catch (Exception e) {
            log.error("Unexpected error fetching Compute Optimizer recommendations for region {}: {}", region, e.getMessage());
            return Map.of();
        }
    }

    /**
     * The account's Compute Optimizer enrollment in a region (E08-T11): e.g. "Active" or
     * "Inactive". Free, read-only. Throws on an AWS error so the caller can report it.
     */
    public String getEnrollmentStatus(String region) {
        return getClient(region)
                .getEnrollmentStatus(software.amazon.awssdk.services.computeoptimizer.model.GetEnrollmentStatusRequest.builder().build())
                .statusAsString();
    }

    /**
     * Parses recommendations keyed by instance ID (extracted from each recommendation's ARN,
     * since the request doesn't filter by instance and the response covers every EC2 instance
     * Compute Optimizer knows about). Only instances with at least one recommendation option are
     * included — an instance already at {@code OPTIMIZED} may have no smaller option to suggest.
     */
    Map<String, Recommendation> parseRecommendations(GetEc2InstanceRecommendationsResponse response) {
        Map<String, Recommendation> result = new LinkedHashMap<>();
        for (InstanceRecommendation rec : response.instanceRecommendations()) {
            String instanceId = extractInstanceId(rec.instanceArn());
            if (instanceId == null) {
                continue;
            }
            // Only an over-provisioned instance is a scale-down; an under-provisioned one's
            // options are larger (M11, E08-T05).
            if (rec.finding() != software.amazon.awssdk.services.computeoptimizer.model.Finding.OVERPROVISIONED) {
                continue;
            }
            List<String> options = rec.recommendationOptions().stream()
                    .sorted(Comparator.comparing(InstanceRecommendationOption::rank))
                    .map(InstanceRecommendationOption::instanceType)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            if (options.isEmpty()) {
                continue;
            }
            result.put(instanceId, new Recommendation(instanceId, rec.findingAsString(), options));
        }
        return result;
    }

    private String extractInstanceId(String instanceArn) {
        if (instanceArn == null) {
            return null;
        }
        int slash = instanceArn.lastIndexOf('/');
        return slash >= 0 ? instanceArn.substring(slash + 1) : instanceArn;
    }

    private ComputeOptimizerClient getClient(String region) {
        return clientCache.computeIfAbsent(region, clientFactory);
    }

    private ComputeOptimizerClient buildClient(String r) {
        return ComputeOptimizerClient.builder()
                .region(Region.of(r))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofSeconds(30))
                        .apiCallAttemptTimeout(Duration.ofSeconds(25))
                        .build())
                .build();
    }
}
