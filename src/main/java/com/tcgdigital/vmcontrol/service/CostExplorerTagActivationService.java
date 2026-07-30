package com.tcgdigital.vmcontrol.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.costexplorer.model.CostAllocationTagStatus;
import software.amazon.awssdk.services.costexplorer.model.CostAllocationTagStatusEntry;
import software.amazon.awssdk.services.costexplorer.model.UpdateCostAllocationTagsStatusRequest;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Activates cost-allocation tag keys in AWS Cost Explorer so a tag can be used as a grouping
 * dimension there — a plain resource tag isn't usable for that on its own (AWS requires this
 * one-time activation, with up to 24h propagation delay before the tag appears in results).
 *
 * Cost Explorer's API is fixed to us-east-1 regardless of the account's operating region —
 * unlike every other AWS client in this codebase, this one is never parameterized by VM region.
 */
@Service
public class CostExplorerTagActivationService {

    private static final Logger log = LoggerFactory.getLogger(CostExplorerTagActivationService.class);

    @Value("${aws.access-key:}")
    private String accessKey;

    @Value("${aws.secret-key:}")
    private String secretKey;

    private volatile CostExplorerClient client;

    // Activation only needs to be requested once per key, ever — re-requesting on every
    // reconciliation cycle would just be a wasted billed API call for an already-active tag.
    private final AtomicBoolean activationRequested = new AtomicBoolean(false);

    public boolean isAvailable() {
        return accessKey != null && !accessKey.isEmpty()
                && secretKey != null && !secretKey.isEmpty();
    }

    public void ensureTagKeysActivated(List<String> tagKeys) {
        if (tagKeys.isEmpty() || !isAvailable()) {
            return;
        }
        if (!activationRequested.compareAndSet(false, true)) {
            return;
        }
        try {
            List<CostAllocationTagStatusEntry> entries = tagKeys.stream()
                    .map(key -> CostAllocationTagStatusEntry.builder()
                            .tagKey(key)
                            .status(CostAllocationTagStatus.ACTIVE)
                            .build())
                    .toList();

            getClient().updateCostAllocationTagsStatus(UpdateCostAllocationTagsStatusRequest.builder()
                    .costAllocationTagsStatus(entries)
                    .build());

            log.info("Requested Cost Explorer activation for cost-allocation tag key(s): {} " +
                    "(may take up to 24h to appear in Cost Explorer results)", tagKeys);
        } catch (Exception e) {
            log.warn("Could not activate cost-allocation tag key(s) {} — may already be active, or " +
                    "ce:UpdateCostAllocationTagsStatus is not granted on the current AWS credential: {}",
                    tagKeys, e.getMessage());
        }
    }

    private CostExplorerClient getClient() {
        CostExplorerClient existing = client;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (client == null) {
                client = CostExplorerClient.builder()
                        .region(Region.US_EAST_1)
                        .credentialsProvider(StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(accessKey, secretKey)))
                        .overrideConfiguration(ClientOverrideConfiguration.builder()
                                .apiCallTimeout(Duration.ofSeconds(30))
                                .apiCallAttemptTimeout(Duration.ofSeconds(25))
                                .build())
                        .build();
            }
            return client;
        }
    }
}
