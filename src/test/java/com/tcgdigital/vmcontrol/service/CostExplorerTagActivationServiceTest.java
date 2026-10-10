package com.tcgdigital.vmcontrol.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.costexplorer.model.CostAllocationTag;
import software.amazon.awssdk.services.costexplorer.model.CostAllocationTagStatus;
import software.amazon.awssdk.services.costexplorer.model.CostExplorerException;
import software.amazon.awssdk.services.costexplorer.model.ListCostAllocationTagsRequest;
import software.amazon.awssdk.services.costexplorer.model.ListCostAllocationTagsResponse;
import software.amazon.awssdk.services.costexplorer.model.UpdateCostAllocationTagsStatusRequest;
import software.amazon.awssdk.services.costexplorer.model.UpdateCostAllocationTagsStatusResponse;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cost-allocation tag activation retries after a failure instead of giving up for the life of the
 * process, skips the update when the keys are already active, and stops once it succeeded (E08-T02).
 */
@ExtendWith(MockitoExtension.class)
class CostExplorerTagActivationServiceTest {

    private static final List<String> KEYS = List.of("tcg:managed-by", "tcg:environment", "tcg:team");

    @Mock private CostExplorerClient ce;

    private Instant now = Instant.parse("2026-10-07T02:30:00Z");
    private CostExplorerTagActivationService service;

    /** A clock that reads the test's mutable {@code now}. */
    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    };

    @BeforeEach
    void setUp() {
        service = new CostExplorerTagActivationService(new CostExplorerClientProvider(ce), 360, clock);
    }

    private void listed(CostAllocationTagStatus status) {
        when(ce.listCostAllocationTags(any(ListCostAllocationTagsRequest.class))).thenReturn(
                ListCostAllocationTagsResponse.builder().costAllocationTags(KEYS.stream()
                        .map(k -> CostAllocationTag.builder().tagKey(k).status(status).build()).toList()).build());
    }

    @Test
    void afterAFailureItWaitsForTheBackoffThenRetries() {
        listed(CostAllocationTagStatus.INACTIVE);
        when(ce.updateCostAllocationTagsStatus(any(UpdateCostAllocationTagsStatusRequest.class)))
                .thenThrow(CostExplorerException.builder().message("AccessDenied").build())
                .thenReturn(UpdateCostAllocationTagsStatusResponse.builder().build());

        service.ensureTagKeysActivated(KEYS);
        now = now.plus(Duration.ofMinutes(60));
        service.ensureTagKeysActivated(KEYS); // inside the 6 h backoff: no call
        verify(ce, times(1)).updateCostAllocationTagsStatus(any(UpdateCostAllocationTagsStatusRequest.class));

        now = now.plus(Duration.ofMinutes(301));
        service.ensureTagKeysActivated(KEYS);
        verify(ce, times(2)).updateCostAllocationTagsStatus(any(UpdateCostAllocationTagsStatusRequest.class));
    }

    @Test
    void alreadyActiveKeysNeedNoUpdate() {
        listed(CostAllocationTagStatus.ACTIVE);

        service.ensureTagKeysActivated(KEYS);
        now = now.plus(Duration.ofDays(1));
        service.ensureTagKeysActivated(KEYS);

        verify(ce, never()).updateCostAllocationTagsStatus(any(UpdateCostAllocationTagsStatusRequest.class));
        verify(ce, times(1)).listCostAllocationTags(any(ListCostAllocationTagsRequest.class));
    }

    @Test
    void afterASuccessLaterCallsAreNoOps() {
        listed(CostAllocationTagStatus.INACTIVE);
        when(ce.updateCostAllocationTagsStatus(any(UpdateCostAllocationTagsStatusRequest.class)))
                .thenReturn(UpdateCostAllocationTagsStatusResponse.builder().build());

        service.ensureTagKeysActivated(KEYS);
        now = now.plus(Duration.ofDays(2));
        service.ensureTagKeysActivated(KEYS);

        verify(ce, times(1)).updateCostAllocationTagsStatus(any(UpdateCostAllocationTagsStatusRequest.class));
    }

    @Test
    void anUnconfiguredServiceCallsNothing() {
        new CostExplorerTagActivationService(new CostExplorerClientProvider("", ""), 360, clock).ensureTagKeysActivated(KEYS);

        verify(ce, never()).listCostAllocationTags(any(ListCostAllocationTagsRequest.class));
    }
}
