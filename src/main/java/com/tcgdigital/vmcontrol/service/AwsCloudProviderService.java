package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.VmStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AWS EC2 implementation of CloudProviderService.
 */
@Service
public class AwsCloudProviderService implements CloudProviderService {

    private static final Logger log = LoggerFactory.getLogger(AwsCloudProviderService.class);

    private static final int STATUS_CHECK_TIMEOUT_MS = 300_000;  // 5 minutes
    private static final int STOP_TIMEOUT_MS = 180_000;          // 3 minutes

    // Overridable via ReflectionTestUtils in tests
    @Value("${aws.status-check.poll-interval-ms:10000}")
    private int statusCheckPollIntervalMs;

    @Value("${aws.stop.poll-interval-ms:10000}")
    private int stopPollIntervalMs;

    @Value("${aws.access-key:}")
    private String accessKey;

    @Value("${aws.secret-key:}")
    private String secretKey;

    @Value("${aws.region:us-east-1}")
    private String defaultRegion;

    private final Map<String, Ec2Client> clientCache = new ConcurrentHashMap<>();

    @Override
    public CloudProvider getProvider() {
        return CloudProvider.AWS;
    }

    @Override
    public CompletableFuture<VmOperationResult> startVm(String providerVmId, String region) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Ec2Client ec2 = getEc2Client(region);

                StartInstancesRequest request = StartInstancesRequest.builder()
                        .instanceIds(providerVmId)
                        .build();

                StartInstancesResponse response = ec2.startInstances(request);

                String requestId = response.responseMetadata() != null
                        ? response.responseMetadata().requestId() : null;

                if (!response.startingInstances().isEmpty()) {
                    InstanceStateChange stateChange = response.startingInstances().get(0);
                    log.info("Started AWS EC2 instance {}: {} -> {}",
                            providerVmId,
                            stateChange.previousState().nameAsString(),
                            stateChange.currentState().nameAsString());

                    VmStatus finalStatus = waitForStatusChecks(ec2, providerVmId);

                    if (finalStatus == VmStatus.RUNNING) {
                        log.info("AWS EC2 instance {} passed 2/2 status checks — fully operational", providerVmId);
                        return VmOperationResult.success(requestId, VmStatus.RUNNING);
                    } else {
                        log.warn("AWS EC2 instance {} did not pass status checks in time, current status: {}", providerVmId, finalStatus);
                        return VmOperationResult.success(requestId, finalStatus);
                    }
                }

                // startingInstances is empty — instance may already be pending/running.
                // AWS has eventual consistency: DescribeInstances may still show STOPPED for
                // 1-3 s after StartInstances is accepted. Retry a few times before giving up.
                for (int attempt = 0; attempt < 3; attempt++) {
                    try { Thread.sleep(attempt == 0 ? 2000L : 5000L); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                    VmStatus currentStatus = getVmStatus(providerVmId, region);
                    if (currentStatus == VmStatus.STARTING || currentStatus == VmStatus.RUNNING) {
                        log.info("AWS EC2 instance {} already in state {} (attempt {}), waiting for status checks",
                                providerVmId, currentStatus, attempt + 1);
                        VmStatus finalStatus = waitForStatusChecks(ec2, providerVmId);
                        return VmOperationResult.success(requestId, finalStatus);
                    }
                }
                return VmOperationResult.failure("No instance state change returned");

            } catch (Ec2Exception e) {
                // Instance already in pending/running state — recover instead of failing
                if ("IncorrectInstanceState".equals(e.awsErrorDetails().errorCode())) {
                    VmStatus currentStatus = getVmStatus(providerVmId, region);
                    if (currentStatus == VmStatus.STARTING || currentStatus == VmStatus.RUNNING) {
                        log.info("AWS EC2 instance {} is already {} — waiting for status checks", providerVmId, currentStatus);
                        VmStatus finalStatus = waitForStatusChecks(getEc2Client(region), providerVmId);
                        return VmOperationResult.success(e.requestId(), finalStatus);
                    }
                }
                log.error("Failed to start AWS EC2 instance {}: {}", providerVmId, e.getMessage());
                return VmOperationResult.failure("AWS Error: " + e.awsErrorDetails().errorMessage());
            } catch (Exception e) {
                // Network timeout or similar — AWS may have accepted the request already.
                // Check actual instance state before declaring failure.
                log.error("Failed to start AWS EC2 instance {}: {}", providerVmId, e.getMessage());
                try {
                    VmStatus currentStatus = getVmStatus(providerVmId, region);
                    if (currentStatus == VmStatus.STARTING || currentStatus == VmStatus.RUNNING) {
                        log.info("AWS EC2 instance {} is {} despite exception — waiting for status checks",
                                providerVmId, currentStatus);
                        VmStatus finalStatus = waitForStatusChecks(getEc2Client(region), providerVmId);
                        return VmOperationResult.success(null, finalStatus);
                    }
                } catch (Exception inner) {
                    log.warn("Could not verify instance state after error for {}: {}", providerVmId, inner.getMessage());
                }
                return VmOperationResult.failure("Error: " + e.getMessage());
            }
        });
    }

    @Override
    public CompletableFuture<VmOperationResult> stopVm(String providerVmId, String region, boolean force) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Ec2Client ec2 = getEc2Client(region);

                StopInstancesRequest request = StopInstancesRequest.builder()
                        .instanceIds(providerVmId)
                        .force(force)
                        .build();

                StopInstancesResponse response = ec2.stopInstances(request);

                String stopRequestId = response.responseMetadata() != null
                        ? response.responseMetadata().requestId() : null;

                if (!response.stoppingInstances().isEmpty()) {
                    InstanceStateChange stateChange = response.stoppingInstances().get(0);
                    log.info("Stopped AWS EC2 instance {}: {} -> {}",
                            providerVmId,
                            stateChange.previousState().nameAsString(),
                            stateChange.currentState().nameAsString());

                    VmStatus finalStatus = waitForStopped(ec2, providerVmId);
                    log.info("AWS EC2 instance {} stop complete, final status: {}", providerVmId, finalStatus);
                    return VmOperationResult.success(stopRequestId, finalStatus);
                }

                // stoppingInstances is empty — instance may already be stopping or stopped
                // (double-click, prior op, or API consistency lag)
                for (int attempt = 0; attempt < 3; attempt++) {
                    try { Thread.sleep(attempt == 0 ? 2000L : 5000L); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                    VmStatus currentStatus = getVmStatus(providerVmId, region);
                    if (currentStatus == VmStatus.STOPPING) {
                        log.info("AWS EC2 instance {} already STOPPING (attempt {}), waiting to complete",
                                providerVmId, attempt + 1);
                        return VmOperationResult.success(stopRequestId, waitForStopped(ec2, providerVmId));
                    }
                    if (currentStatus == VmStatus.STOPPED) {
                        log.info("AWS EC2 instance {} already STOPPED", providerVmId);
                        return VmOperationResult.success(stopRequestId, VmStatus.STOPPED);
                    }
                }
                return VmOperationResult.failure("No instance state change returned");

            } catch (Ec2Exception e) {
                if ("IncorrectInstanceState".equals(e.awsErrorDetails().errorCode())) {
                    VmStatus currentStatus = getVmStatus(providerVmId, region);
                    if (currentStatus == VmStatus.STOPPING || currentStatus == VmStatus.STOPPED) {
                        log.info("AWS EC2 instance {} is already {} — treating stop as success", providerVmId, currentStatus);
                        VmStatus finalStatus = currentStatus == VmStatus.STOPPING
                                ? waitForStopped(getEc2Client(region), providerVmId) : VmStatus.STOPPED;
                        return VmOperationResult.success(e.requestId(), finalStatus);
                    }
                }
                log.error("Failed to stop AWS EC2 instance {}: {}", providerVmId, e.getMessage());
                return VmOperationResult.failure("AWS Error: " + e.awsErrorDetails().errorMessage());
            } catch (Exception e) {
                log.error("Failed to stop AWS EC2 instance {}: {}", providerVmId, e.getMessage());
                try {
                    VmStatus currentStatus = getVmStatus(providerVmId, region);
                    if (currentStatus == VmStatus.STOPPING || currentStatus == VmStatus.STOPPED) {
                        log.info("AWS EC2 instance {} is {} despite exception — treating stop as success", providerVmId, currentStatus);
                        VmStatus finalStatus = currentStatus == VmStatus.STOPPING
                                ? waitForStopped(getEc2Client(region), providerVmId) : VmStatus.STOPPED;
                        return VmOperationResult.success(null, finalStatus);
                    }
                } catch (Exception inner) {
                    log.warn("Could not verify instance state after error for {}: {}", providerVmId, inner.getMessage());
                }
                return VmOperationResult.failure("Error: " + e.getMessage());
            }
        });
    }

    /**
     * Wait for EC2 instance to pass 2/2 status checks (System + Instance).
     * Polls every 10 seconds, times out after 5 minutes.
     */
    private VmStatus waitForStatusChecks(Ec2Client ec2, String instanceId) {
        long startTime = System.currentTimeMillis();
        VmStatus lastKnownStatus = VmStatus.STARTING;

        while (System.currentTimeMillis() - startTime < STATUS_CHECK_TIMEOUT_MS) {
            try {
                Thread.sleep(statusCheckPollIntervalMs);

                DescribeInstanceStatusRequest statusRequest = DescribeInstanceStatusRequest.builder()
                        .instanceIds(instanceId)
                        .build();

                DescribeInstanceStatusResponse statusResponse = ec2.describeInstanceStatus(statusRequest);

                if (!statusResponse.instanceStatuses().isEmpty()) {
                    InstanceStatus instanceStatus = statusResponse.instanceStatuses().get(0);

                    SummaryStatus systemCheck = instanceStatus.systemStatus().status();
                    SummaryStatus instanceCheck = instanceStatus.instanceStatus().status();
                    String instanceState = instanceStatus.instanceState().nameAsString();

                    log.debug("Instance {} — state: {}, system: {}, instance: {}",
                            instanceId, instanceState, systemCheck, instanceCheck);

                    if (systemCheck == SummaryStatus.OK && instanceCheck == SummaryStatus.OK) {
                        return VmStatus.RUNNING;
                    }

                    // Update status based on current state
                    lastKnownStatus = mapAwsStateToVmStatus(instanceStatus.instanceState().name());
                } else {
                    // Instance may not yet appear in describeInstanceStatus (still pending)
                    log.debug("Instance {} not yet reporting status checks, still starting...", instanceId);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Status check polling interrupted for instance {}", instanceId);
                return lastKnownStatus;
            } catch (Exception e) {
                log.warn("Error polling status checks for instance {}: {}", instanceId, e.getMessage());
                // Continue polling — transient errors are common during startup
            }
        }

        log.warn("Timed out waiting for status checks on instance {} after {}ms", instanceId, STATUS_CHECK_TIMEOUT_MS);
        return lastKnownStatus;
    }

    /**
     * Wait for EC2 instance to reach STOPPED state.
     * Polls every 10 seconds, times out after 3 minutes.
     */
    private VmStatus waitForStopped(Ec2Client ec2, String instanceId) {
        long startTime = System.currentTimeMillis();
        VmStatus lastKnownStatus = VmStatus.STOPPING;

        while (System.currentTimeMillis() - startTime < STOP_TIMEOUT_MS) {
            try {
                Thread.sleep(stopPollIntervalMs);

                DescribeInstancesRequest request = DescribeInstancesRequest.builder()
                        .instanceIds(instanceId)
                        .build();

                DescribeInstancesResponse response = ec2.describeInstances(request);

                if (!response.reservations().isEmpty() && !response.reservations().get(0).instances().isEmpty()) {
                    Instance instance = response.reservations().get(0).instances().get(0);
                    lastKnownStatus = mapAwsStateToVmStatus(instance.state().name());

                    log.debug("Instance {} — state: {}", instanceId, instance.state().nameAsString());

                    if (instance.state().name() == InstanceStateName.STOPPED) {
                        return VmStatus.STOPPED;
                    }
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Stop polling interrupted for instance {}", instanceId);
                return lastKnownStatus;
            } catch (Exception e) {
                log.warn("Error polling state for instance {}: {}", instanceId, e.getMessage());
            }
        }

        log.warn("Timed out waiting for instance {} to stop after {}ms", instanceId, STOP_TIMEOUT_MS);
        return lastKnownStatus;
    }

    @Override
    public VmStatus getVmStatus(String providerVmId, String region) {
        try {
            Ec2Client ec2 = getEc2Client(region);

            DescribeInstancesRequest request = DescribeInstancesRequest.builder()
                    .instanceIds(providerVmId)
                    .build();

            DescribeInstancesResponse response = ec2.describeInstances(request);

            if (!response.reservations().isEmpty() && !response.reservations().get(0).instances().isEmpty()) {
                Instance instance = response.reservations().get(0).instances().get(0);
                return mapAwsStateToVmStatus(instance.state().name());
            }

            // VM not found in cloud - may have been deleted
            log.warn("AWS EC2 instance {} not found in region {}", providerVmId, region);
            return VmStatus.NOT_FOUND;

        } catch (Ec2Exception e) {
            // InvalidInstanceID.NotFound means the instance doesn't exist
            if ("InvalidInstanceID.NotFound".equals(e.awsErrorDetails().errorCode())) {
                log.warn("AWS EC2 instance {} does not exist: {}", providerVmId, e.getMessage());
                return VmStatus.NOT_FOUND;
            }
            log.error("AWS API error for instance {}: {}", providerVmId, e.getMessage());
            return VmStatus.UNKNOWN;

        } catch (Exception e) {
            log.error("Failed to get status for AWS EC2 instance {}: {}", providerVmId, e.getMessage());
            return VmStatus.UNKNOWN;
        }
    }

    @Override
    public String getVmName(String providerVmId, String region) {
        try {
            Ec2Client ec2 = getEc2Client(region);

            DescribeInstancesRequest request = DescribeInstancesRequest.builder()
                    .instanceIds(providerVmId)
                    .build();

            DescribeInstancesResponse response = ec2.describeInstances(request);

            if (!response.reservations().isEmpty() && !response.reservations().get(0).instances().isEmpty()) {
                Instance instance = response.reservations().get(0).instances().get(0);

                // Find the "Name" tag
                return instance.tags().stream()
                        .filter(tag -> "Name".equals(tag.key()))
                        .map(Tag::value)
                        .findFirst()
                        .orElse(null);
            }

            return null;

        } catch (Exception e) {
            log.error("Failed to get name for AWS EC2 instance {}: {}", providerVmId, e.getMessage());
            return null;
        }
    }

    @Override
    public boolean isAvailable() {
        return accessKey != null && !accessKey.isEmpty()
                && secretKey != null && !secretKey.isEmpty();
    }

    /**
     * Batch-fetches status for multiple EC2 instances with a single DescribeInstances call per chunk
     * instead of one API call per VM.  Falls back gracefully: if the call fails, returns an empty
     * map and the caller will retry individually.
     */
    @Override
    public Map<String, VmStatus> getVmStatusBatch(List<String> instanceIds, String region) {
        if (instanceIds.isEmpty()) return Collections.emptyMap();

        Map<String, VmStatus> result = new HashMap<>();
        try {
            Ec2Client ec2 = getEc2Client(region);

            // EC2 accepts up to 1 000 IDs; chunk at 200 to stay well inside the limit
            int chunkSize = 200;
            for (int i = 0; i < instanceIds.size(); i += chunkSize) {
                List<String> chunk = instanceIds.subList(i, Math.min(i + chunkSize, instanceIds.size()));

                DescribeInstancesResponse response = ec2.describeInstances(
                        DescribeInstancesRequest.builder()
                                .instanceIds(chunk)
                                .build());

                for (Reservation reservation : response.reservations()) {
                    for (Instance instance : reservation.instances()) {
                        result.put(instance.instanceId(),
                                mapAwsStateToVmStatus(instance.state().name()));
                    }
                }
            }

            // Instance IDs absent from the response no longer exist in EC2
            for (String id : instanceIds) {
                result.putIfAbsent(id, VmStatus.NOT_FOUND);
            }

            log.debug("Batch DescribeInstances: {} IDs → {} results in {}", instanceIds.size(), result.size(), region);

        } catch (Ec2Exception e) {
            log.error("Batch DescribeInstances failed for region {}: {}", region, e.getMessage());
            // Return empty — StateSyncService will fall back to per-VM fetch for this group
        } catch (Exception e) {
            log.error("Batch status fetch error for region {}: {}", region, e.getMessage());
        }

        return result;
    }

    /**
     * Discovers all non-terminated EC2 instances in a region whose Name tag starts with namePrefix.
     * Pass null/blank namePrefix to return all non-terminated instances.
     */
    public java.util.List<Instance> discoverInstancesByNamePrefix(String region, String namePrefix) {
        try {
            Ec2Client ec2 = getEc2Client(region);
            java.util.List<Filter> filters = new java.util.ArrayList<>();
            filters.add(Filter.builder()
                    .name("instance-state-name")
                    .values("pending", "running", "stopping", "stopped")
                    .build());
            if (namePrefix != null && !namePrefix.isBlank()) {
                filters.add(Filter.builder()
                        .name("tag:Name")
                        .values(namePrefix + "*")
                        .build());
            }
            java.util.List<Instance> result = new java.util.ArrayList<>();
            software.amazon.awssdk.services.ec2.paginators.DescribeInstancesIterable pages =
                    ec2.describeInstancesPaginator(DescribeInstancesRequest.builder()
                            .filters(filters).build());
            for (DescribeInstancesResponse page : pages) {
                for (software.amazon.awssdk.services.ec2.model.Reservation r : page.reservations()) {
                    result.addAll(r.instances());
                }
            }
            log.debug("discoverInstancesByNamePrefix(prefix='{}') found {} instance(s) in {}", namePrefix, result.size(), region);
            return result;
        } catch (Exception e) {
            log.error("Failed to discover instances by name prefix '{}' in region {}: {}", namePrefix, region, e.getMessage());
            return java.util.List.of();
        }
    }

    /**
     * Discovers all non-terminated EC2 instances in a region that carry the given tag key/value.
     * Uses the SDK paginator so results beyond the 1000-item page limit are not missed.
     */
    public java.util.List<Instance> discoverTaggedInstances(String region, String tagKey, String tagValue) {
        try {
            Ec2Client ec2 = getEc2Client(region);

            Filter tagFilter = Filter.builder()
                    .name("tag:" + tagKey)
                    .values(tagValue)
                    .build();
            Filter stateFilter = Filter.builder()
                    .name("instance-state-name")
                    .values("pending", "running", "stopping", "stopped")
                    .build();

            java.util.List<Instance> result = new java.util.ArrayList<>();
            software.amazon.awssdk.services.ec2.paginators.DescribeInstancesIterable pages =
                    ec2.describeInstancesPaginator(DescribeInstancesRequest.builder()
                            .filters(tagFilter, stateFilter)
                            .build());

            for (DescribeInstancesResponse page : pages) {
                for (software.amazon.awssdk.services.ec2.model.Reservation r : page.reservations()) {
                    result.addAll(r.instances());
                }
            }

            log.debug("Discovered {} tagged instances in region {} with tag {}={}", result.size(), region, tagKey, tagValue);
            return result;

        } catch (Exception e) {
            log.error("Failed to discover tagged instances in region {} (tag {}={}): {}", region, tagKey, tagValue, e.getMessage());
            return java.util.List.of();
        }
    }

    @Override
    public java.util.List<String> discoverInstanceIds(java.util.List<String> regions) {
        java.util.List<String> discovered = new java.util.ArrayList<>();
        for (String region : regions) {
            try {
                Ec2Client ec2 = getEc2Client(region);
                DescribeInstancesResponse response = ec2.describeInstances(
                        DescribeInstancesRequest.builder().build());
                for (software.amazon.awssdk.services.ec2.model.Reservation r : response.reservations()) {
                    for (Instance instance : r.instances()) {
                        InstanceStateName state = instance.state().name();
                        if (state != InstanceStateName.TERMINATED && state != InstanceStateName.SHUTTING_DOWN) {
                            discovered.add(instance.instanceId());
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Failed to discover EC2 instances in region {}: {}", region, e.getMessage());
            }
        }
        return discovered;
    }

    private Ec2Client getEc2Client(String region) {
        String effectiveRegion = region != null && !region.isEmpty() ? region : defaultRegion;
        return clientCache.computeIfAbsent(effectiveRegion, r -> Ec2Client.builder()
                .region(Region.of(r))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofSeconds(30))
                        .apiCallAttemptTimeout(Duration.ofSeconds(25))
                        .build())
                .build());
    }

    private VmStatus mapAwsStateToVmStatus(InstanceStateName stateName) {
        if (stateName == null) {
            return VmStatus.UNKNOWN;
        }

        return switch (stateName) {
            case RUNNING -> VmStatus.RUNNING;
            case STOPPED -> VmStatus.STOPPED;
            case PENDING -> VmStatus.STARTING;
            case STOPPING, SHUTTING_DOWN -> VmStatus.STOPPING;
            case TERMINATED -> VmStatus.TERMINATED;
            default -> VmStatus.UNKNOWN;
        };
    }
}

