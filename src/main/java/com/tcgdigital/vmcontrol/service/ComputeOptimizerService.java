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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Overlays AWS Compute Optimizer's real, ML-driven EC2 rightsizing recommendations onto the
 * home-grown CPU-threshold rule in {@link CostEstimationService} — a second signal, never a
 * replacement: Compute Optimizer needs the account opted in and ~14 days of data before it
 * returns anything, and has no recommendation type for EKS managed node groups at all, so
 * {@code AWS_EKS} VMs always stay on the CPU-threshold rule regardless of this service.
 *
 * Disabled by default ({@code cost.optimizer.enabled=false}) — unlike Cost Explorer, Compute
 * Optimizer's own API calls aren't billed per-request, but it's still an extra AWS dependency
 * this feature can run without.
 */
@Service
public class ComputeOptimizerService {

    private static final Logger log = LoggerFactory.getLogger(ComputeOptimizerService.class);

    @Value("${aws.access-key:}")
    private String accessKey;

    @Value("${aws.secret-key:}")
    private String secretKey;

    @Value("${cost.optimizer.enabled:false}")
    private boolean enabled;

    private final Map<String, ComputeOptimizerClient> clientCache = new ConcurrentHashMap<>();

    public record Recommendation(String instanceId, String suggestedInstanceType, String finding) {
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
        try {
            GetEc2InstanceRecommendationsResponse response = getClient(region)
                    .getEC2InstanceRecommendations(GetEc2InstanceRecommendationsRequest.builder().build());
            return parseRecommendations(response);
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
            String suggested = rec.recommendationOptions().stream()
                    .min(Comparator.comparing(InstanceRecommendationOption::rank))
                    .map(InstanceRecommendationOption::instanceType)
                    .orElse(null);
            if (suggested == null) {
                continue;
            }
            result.put(instanceId, new Recommendation(instanceId, suggested, rec.findingAsString()));
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
        return clientCache.computeIfAbsent(region, r -> ComputeOptimizerClient.builder()
                .region(Region.of(r))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofSeconds(30))
                        .apiCallAttemptTimeout(Duration.ofSeconds(25))
                        .build())
                .build());
    }
}
