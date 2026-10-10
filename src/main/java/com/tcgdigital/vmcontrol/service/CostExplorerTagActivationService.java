package com.tcgdigital.vmcontrol.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.costexplorer.model.CostAllocationTagStatus;
import software.amazon.awssdk.services.costexplorer.model.CostAllocationTagStatusEntry;
import software.amazon.awssdk.services.costexplorer.model.UpdateCostAllocationTagsStatusRequest;

import java.util.List;

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

    private final CostExplorerClientProvider clientProvider;
    /** Minutes to wait after a failed activation before trying again (E08-T02). */
    private final long retryMinutes;
    private final java.time.Clock clock;

    // Done once the keys are active; a failure is retried after the backoff (E08-T02).
    private volatile boolean activated;
    private volatile java.time.Instant lastAttempt;

    @org.springframework.beans.factory.annotation.Autowired
    public CostExplorerTagActivationService(CostExplorerClientProvider clientProvider,
                                            @Value("${cost.tagging.activation-retry-minutes:360}") long retryMinutes) {
        this(clientProvider, retryMinutes, java.time.Clock.systemUTC());
    }

    CostExplorerTagActivationService(CostExplorerClientProvider clientProvider, long retryMinutes, java.time.Clock clock) {
        this.clientProvider = clientProvider;
        this.retryMinutes = retryMinutes;
        this.clock = clock;
    }

    public boolean isAvailable() {
        return clientProvider.isConfigured();
    }

    /**
     * Makes sure the cost-allocation tag keys are active in Cost Explorer. Already-active keys
     * (ListCostAllocationTags) need no update call; a failed update is retried after
     * cost.tagging.activation-retry-minutes rather than never again; once active, a no-op.
     */
    public void ensureTagKeysActivated(List<String> tagKeys) {
        if (tagKeys.isEmpty() || !isAvailable() || activated) {
            return;
        }
        java.time.Instant now = clock.instant();
        java.time.Instant previous = lastAttempt;
        if (previous != null && previous.plus(java.time.Duration.ofMinutes(retryMinutes)).isAfter(now)) {
            return;
        }
        lastAttempt = now;
        try {
            java.util.Set<String> active = clientProvider.client().listCostAllocationTags(
                            software.amazon.awssdk.services.costexplorer.model.ListCostAllocationTagsRequest.builder()
                                    .tagKeys(tagKeys).build())
                    .costAllocationTags().stream()
                    .filter(t -> t.status() == CostAllocationTagStatus.ACTIVE)
                    .map(software.amazon.awssdk.services.costexplorer.model.CostAllocationTag::tagKey)
                    .collect(java.util.stream.Collectors.toSet());
            if (active.containsAll(tagKeys)) {
                activated = true;
                return;
            }
        } catch (Exception e) {
            log.debug("Could not list cost-allocation tags (will try to activate anyway): {}", e.getMessage());
        }
        try {
            List<CostAllocationTagStatusEntry> entries = tagKeys.stream()
                    .map(key -> CostAllocationTagStatusEntry.builder()
                            .tagKey(key)
                            .status(CostAllocationTagStatus.ACTIVE)
                            .build())
                    .toList();

            var response = clientProvider.client().updateCostAllocationTagsStatus(UpdateCostAllocationTagsStatusRequest.builder()
                    .costAllocationTagsStatus(entries)
                    .build());
            if (response.hasErrors() && !response.errors().isEmpty()) {
                log.warn("Cost Explorer refused to activate some tag key(s) {}; will retry in {} minutes: {}",
                        tagKeys, retryMinutes, response.errors());
                return;
            }
            activated = true;

            log.info("Requested Cost Explorer activation for cost-allocation tag key(s): {} " +
                    "(may take up to 24h to appear in Cost Explorer results)", tagKeys);
        } catch (Exception e) {
            log.warn("Could not activate cost-allocation tag key(s) {} — ce:UpdateCostAllocationTagsStatus may " +
                    "not be granted on the current AWS credential; will retry in {} minutes: {}",
                    tagKeys, retryMinutes, e.getMessage());
        }
    }
}
