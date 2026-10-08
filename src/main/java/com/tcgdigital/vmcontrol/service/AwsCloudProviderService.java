package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.exception.OperationCancelledException;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AWS EC2 implementation of CloudProviderService.
 */
@Service
public class AwsCloudProviderService implements CloudProviderService {

    private static final Logger log = LoggerFactory.getLogger(AwsCloudProviderService.class);


    // Overridable via ReflectionTestUtils in tests.
    // EC2 status checks (system + instance + attached-EBS) routinely take 2-5+ min after an
    // instance reaches RUNNING; a too-short window here reports a perfectly healthy VM as a
    // failed start. Keep below vm.state.sync.stale-transitional-minutes so a genuinely stuck
    // transitional VM is still eventually reconciled.
    @Value("${aws.status-check.timeout-ms:900000}")
    private int statusCheckTimeoutMs;

    @Value("${aws.status-check.poll-interval-ms:10000}")
    private int statusCheckPollIntervalMs;

    /** How long a stop may take to reach STOPPED before the step fails as timed out. */
    @Value("${aws.stop.timeout-ms:180000}")
    private long stopTimeoutMs = 180_000;

    /**
     * How long a start may keep reporting STOPPED before failing (AWS can lag right after
     * StartInstances); once the instance was seen pending/running, falling back to stopped
     * fails at once (M9).
     */
    @Value("${aws.start.stopped-grace-ms:60000}")
    private long startStoppedGraceMs = 60_000;

    @Value("${aws.stop.poll-interval-ms:10000}")
    private int stopPollIntervalMs;

    @Value("${aws.access-key:}")
    private String accessKey;

    @Value("${aws.secret-key:}")
    private String secretKey;

    @Value("${aws.region:us-east-1}")
    private String defaultRegion;

    private final Map<String, Ec2Client> clientCache = new ConcurrentHashMap<>();

    // Runs the start/stop bodies (each of which blocks in a status-check poll loop for up to
    // statusCheckTimeoutMs). A dedicated pool rather than the common ForkJoinPool so that a
    // batch of concurrent VM operations actually polls in parallel instead of being throttled
    // to the common pool's small parallelism. Upstream concurrency is already bounded by
    // vmOperationExecutor (vm.operations.parallelism) and one-operation-per-environment.
    private final ExecutorService operationExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "aws-op-" + System.nanoTime());
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public CloudProvider getProvider() {
        return CloudProvider.AWS;
    }

    @Override
    public CompletableFuture<VmOperationResult> startVm(String providerVmId, String region) {
        return startVm(providerVmId, region, null);
    }

    @Override
    public CompletableFuture<VmOperationResult> startVm(String providerVmId, String region,
                                                        OperationProgressListener progressListener) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Ec2Client ec2 = getEc2Client(region);

                notifyProgress(progressListener, VmOperationProgress.of(VmStatus.STARTING, "Start requested", 10));

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

                    notifyProgress(progressListener, VmOperationProgress.of(VmStatus.STARTING, "EC2 starting", 30));
                    StartReadiness finalReadiness = waitForStartReadiness(ec2, providerVmId, progressListener);
                    return toStartResult(requestId, finalReadiness, providerVmId);
                }

                // startingInstances is empty — instance may already be pending/running.
                // AWS has eventual consistency: DescribeInstances may still show STOPPED for
                // 1-3 s after StartInstances is accepted. Retry a few times before giving up.
                for (int attempt = 0; attempt < 3; attempt++) {
                    try { Thread.sleep(attempt == 0 ? 2000L : 5000L); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                    VmStatus currentStatus = getVmStatus(providerVmId, region);
                    if (currentStatus == VmStatus.STARTING || currentStatus == VmStatus.RUNNING) {
                        log.info("AWS EC2 instance {} already in state {} (attempt {}), waiting for RUNNING",
                                providerVmId, currentStatus, attempt + 1);
                        StartReadiness finalReadiness = waitForStartReadiness(ec2, providerVmId, progressListener);
                        return toStartResult(requestId, finalReadiness, providerVmId);
                    }
                }
                return VmOperationResult.failure("No instance state change returned");

            } catch (OperationCancelledException e) {
                log.info("Start of AWS EC2 instance {} aborted: {}", providerVmId, e.getMessage());
                throw e;
            } catch (Ec2Exception e) {
                // Instance already in pending/running state — recover instead of failing
                if ("IncorrectInstanceState".equals(e.awsErrorDetails().errorCode())) {
                    VmStatus currentStatus = getVmStatus(providerVmId, region);
                    if (currentStatus == VmStatus.STARTING || currentStatus == VmStatus.RUNNING) {
                        log.info("AWS EC2 instance {} is already {} - waiting for RUNNING", providerVmId, currentStatus);
                        StartReadiness finalReadiness = waitForStartReadiness(getEc2Client(region), providerVmId, progressListener);
                        return toStartResult(e.requestId(), finalReadiness, providerVmId);
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
                        log.info("AWS EC2 instance {} is {} despite exception - waiting for RUNNING",
                                providerVmId, currentStatus);
                        StartReadiness finalReadiness = waitForStartReadiness(getEc2Client(region), providerVmId, progressListener);
                        return toStartResult(null, finalReadiness, providerVmId);
                    }
                } catch (Exception inner) {
                    log.warn("Could not verify instance state after error for {}: {}", providerVmId, inner.getMessage());
                }
                return VmOperationResult.failure("Error: " + e.getMessage());
            }
        }, operationExecutor);
    }

    @Override
    public CompletableFuture<VmOperationResult> stopVm(String providerVmId, String region, boolean force) {
        return stopVm(providerVmId, region, force, null);
    }

    @Override
    public CompletableFuture<VmOperationResult> stopVm(String providerVmId, String region, boolean force,
                                                       OperationProgressListener progressListener) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Ec2Client ec2 = getEc2Client(region);

                notifyProgress(progressListener, VmOperationProgress.of(VmStatus.STOPPING, "Stop requested", 10));

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

                    notifyProgress(progressListener, VmOperationProgress.of(VmStatus.STOPPING, "EC2 stopping", 50));
                    VmStatus finalStatus = waitForStopped(ec2, providerVmId, progressListener);
                    if (finalStatus == VmStatus.STOPPED) {
                        notifyProgress(progressListener, VmOperationProgress.of(VmStatus.STOPPED, "Stop completed", 100));
                    }
                    log.info("AWS EC2 instance {} stop finished, final status: {}", providerVmId, finalStatus);
                    return stopResult(stopRequestId, finalStatus);
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
                        notifyProgress(progressListener, VmOperationProgress.of(VmStatus.STOPPING, "EC2 stopping", 50));
                        VmStatus finalStatus = waitForStopped(ec2, providerVmId, progressListener);
                        if (finalStatus == VmStatus.STOPPED) {
                            notifyProgress(progressListener, VmOperationProgress.of(VmStatus.STOPPED, "Stop completed", 100));
                        }
                        return stopResult(stopRequestId, finalStatus);
                    }
                    if (currentStatus == VmStatus.STOPPED) {
                        log.info("AWS EC2 instance {} already STOPPED", providerVmId);
                        notifyProgress(progressListener, VmOperationProgress.of(VmStatus.STOPPED, "Stop completed", 100));
                        return VmOperationResult.success(stopRequestId, VmStatus.STOPPED);
                    }
                }
                return VmOperationResult.failure("No instance state change returned");

            } catch (OperationCancelledException e) {
                log.info("Stop of AWS EC2 instance {} aborted: {}", providerVmId, e.getMessage());
                throw e;
            } catch (Ec2Exception e) {
                if ("IncorrectInstanceState".equals(e.awsErrorDetails().errorCode())) {
                    VmStatus currentStatus = getVmStatus(providerVmId, region);
                    if (currentStatus == VmStatus.STOPPING || currentStatus == VmStatus.STOPPED) {
                        log.info("AWS EC2 instance {} is already {} — waiting for it to stop", providerVmId, currentStatus);
                        VmStatus finalStatus = currentStatus == VmStatus.STOPPING
                                ? waitForStopped(getEc2Client(region), providerVmId, progressListener) : VmStatus.STOPPED;
                        return stopResult(e.requestId(), finalStatus);
                    }
                }
                log.error("Failed to stop AWS EC2 instance {}: {}", providerVmId, e.getMessage());
                return VmOperationResult.failure("AWS Error: " + e.awsErrorDetails().errorMessage());
            } catch (Exception e) {
                log.error("Failed to stop AWS EC2 instance {}: {}", providerVmId, e.getMessage());
                try {
                    VmStatus currentStatus = getVmStatus(providerVmId, region);
                    if (currentStatus == VmStatus.STOPPING || currentStatus == VmStatus.STOPPED) {
                        log.info("AWS EC2 instance {} is {} despite exception — waiting for it to stop", providerVmId, currentStatus);
                        VmStatus finalStatus = currentStatus == VmStatus.STOPPING
                                ? waitForStopped(getEc2Client(region), providerVmId, progressListener) : VmStatus.STOPPED;
                        return stopResult(null, finalStatus);
                    }
                } catch (OperationCancelledException e2) {
                    throw e2;
                } catch (Exception inner) {
                    log.warn("Could not verify instance state after error for {}: {}", providerVmId, inner.getMessage());
                }
                return VmOperationResult.failure("Error: " + e.getMessage());
            }
        }, operationExecutor);
    }

    /**
     * Wait for an EC2 instance to reach RUNNING and, ideally, pass all reported AWS status
     * checks. Polls every {@code aws.status-check.poll-interval-ms} (default 10s), times out
     * after {@code aws.status-check.timeout-ms} (default 15min). {@link #toStartResult} treats
     * reaching RUNNING as success even if status checks (routinely 2-5+ min) haven't all
     * reported OK by then, since the instance is already up and usable.
     *
     * <p>TODO: replace this hand-rolled poll loop with AWS SDK v2 waiters —
     * {@code Ec2Client.waiter().waitUntilInstanceRunning(...)} followed by
     * {@code waitUntilInstanceStatusOk(...)} — for configurable backoff + max-attempts for free.
     */
    private StartReadiness waitForStartReadiness(Ec2Client ec2, String instanceId,
                                                 OperationProgressListener progressListener) {
        long startTime = System.currentTimeMillis();
        VmStatus lastKnownStatus = VmStatus.STARTING;
        int lastChecksPassed = 0;
        int lastChecksTotal = 0;
        boolean seenStarting = false; // AWS reported pending/running at least once

        while (System.currentTimeMillis() - startTime < statusCheckTimeoutMs) {
            try {
                Thread.sleep(statusCheckPollIntervalMs);

                DescribeInstanceStatusRequest statusRequest = DescribeInstanceStatusRequest.builder()
                        .instanceIds(instanceId)
                        .build();

                DescribeInstanceStatusResponse statusResponse = ec2.describeInstanceStatus(statusRequest);

                if (!statusResponse.instanceStatuses().isEmpty()) {
                    InstanceStatus instanceStatus = statusResponse.instanceStatuses().get(0);

                    String instanceState = instanceStatus.instanceState().nameAsString();
                    StatusCheckCount statusChecks = countStatusChecks(instanceStatus);
                    lastChecksPassed = statusChecks.passed();
                    lastChecksTotal = statusChecks.total();
                    SummaryStatus systemCheck = instanceStatus.systemStatus().status();
                    SummaryStatus instanceCheck = instanceStatus.instanceStatus().status();

                    log.debug("Instance {} — state: {}, system: {}, instance: {}",
                            instanceId, instanceState, systemCheck, instanceCheck);

                    lastKnownStatus = mapAwsStateToVmStatus(instanceStatus.instanceState().name());
                    if (lastKnownStatus == VmStatus.RUNNING && lastChecksTotal > 0) {
                        int progress = lastChecksPassed == lastChecksTotal
                                ? 100
                                : Math.min(99, 70 + (int) Math.round(25.0 * lastChecksPassed / lastChecksTotal));
                        VmStatus progressStatus = lastChecksPassed == lastChecksTotal
                                ? VmStatus.RUNNING
                                : VmStatus.STARTING;
                        notifyProgress(progressListener, VmOperationProgress.checks(
                                progressStatus, progress, lastChecksPassed, lastChecksTotal));

                        if (lastChecksPassed == lastChecksTotal) {
                            return new StartReadiness(VmStatus.RUNNING, true, lastChecksPassed, lastChecksTotal);
                        }
                    } else if (lastKnownStatus == VmStatus.RUNNING) {
                        notifyProgress(progressListener, VmOperationProgress.of(VmStatus.STARTING, "EC2 running", 70));
                    } else {
                        notifyProgress(progressListener, VmOperationProgress.of(VmStatus.STARTING, "EC2 starting", 30));
                    }
                } else {
                    // Instance may not yet appear in describeInstanceStatus (still pending)
                    log.debug("Instance {} not yet reporting status checks, still starting...", instanceId);
                    VmStatus currentState = describeInstanceState(ec2, instanceId);
                    if (currentState != VmStatus.UNKNOWN && currentState != VmStatus.NOT_FOUND) {
                        lastKnownStatus = currentState;
                    }
                    if (lastKnownStatus == VmStatus.RUNNING) {
                        notifyProgress(progressListener, VmOperationProgress.of(VmStatus.STARTING, "EC2 running", 70));
                    } else {
                        notifyProgress(progressListener, VmOperationProgress.of(VmStatus.STARTING, "EC2 starting", 30));
                    }
                }

                if (lastKnownStatus == VmStatus.STARTING || lastKnownStatus == VmStatus.RUNNING) {
                    seenStarting = true;
                } else if (lastKnownStatus == VmStatus.STOPPED || lastKnownStatus == VmStatus.TERMINATED) {
                    // Fell back (e.g. InsufficientInstanceCapacity) or never left stopped: fail now
                    // instead of polling for the full status-check timeout (M9).
                    if (seenStarting || System.currentTimeMillis() - startTime > startStoppedGraceMs) {
                        log.warn("Instance {} is {} while starting — giving up", instanceId, lastKnownStatus);
                        return new StartReadiness(lastKnownStatus, false, lastChecksPassed, lastChecksTotal);
                    }
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Start polling interrupted for instance {}", instanceId);
                return new StartReadiness(lastKnownStatus, false, lastChecksPassed, lastChecksTotal);
            } catch (OperationCancelledException e) {
                throw e;
            } catch (Exception e) {
                log.warn("Error polling running state for instance {}: {}", instanceId, e.getMessage());
                // Continue polling — transient errors are common during startup
            }
        }

        log.warn("Timed out waiting for instance {} to pass AWS status checks after {}ms", instanceId, statusCheckTimeoutMs);
        return new StartReadiness(lastKnownStatus, false, lastChecksPassed, lastChecksTotal);
    }

    private VmStatus describeInstanceState(Ec2Client ec2, String instanceId) {
        DescribeInstancesResponse response = ec2.describeInstances(DescribeInstancesRequest.builder()
                .instanceIds(instanceId)
                .build());

        if (!response.reservations().isEmpty() && !response.reservations().get(0).instances().isEmpty()) {
            Instance instance = response.reservations().get(0).instances().get(0);
            return mapAwsStateToVmStatus(instance.state().name());
        }

        return VmStatus.NOT_FOUND;
    }

    private StatusCheckCount countStatusChecks(InstanceStatus instanceStatus) {
        int total = 0;
        int passed = 0;

        if (instanceStatus.systemStatus() != null && instanceStatus.systemStatus().status() != null) {
            total++;
            if (instanceStatus.systemStatus().status() == SummaryStatus.OK) {
                passed++;
            }
        }

        if (instanceStatus.instanceStatus() != null && instanceStatus.instanceStatus().status() != null) {
            total++;
            if (instanceStatus.instanceStatus().status() == SummaryStatus.OK) {
                passed++;
            }
        }

        if (instanceStatus.attachedEbsStatus() != null && instanceStatus.attachedEbsStatus().status() != null) {
            total++;
            if (instanceStatus.attachedEbsStatus().status() == SummaryStatus.OK) {
                passed++;
            }
        }

        return new StatusCheckCount(passed, total);
    }

    private void notifyProgress(OperationProgressListener listener, VmOperationProgress progress) {
        if (listener == null || progress == null) {
            return;
        }
        try {
            listener.onProgress(progress);
        } catch (OperationCancelledException e) {
            // Deliberate signal from the listener that the operation was cancelled — let it
            // unwind the poll loop instead of swallowing it like an ordinary listener failure.
            throw e;
        } catch (Exception e) {
            log.warn("Operation progress listener failed: {}", e.getMessage());
        }
    }

    private record StatusCheckCount(int passed, int total) {
    }

    private record StartReadiness(VmStatus status, boolean ready, int checksPassed, int checksTotal) {
        private String timeoutMessage() {
            if (checksTotal > 0) {
                return "AWS status checks did not pass in time (" + checksPassed + "/" + checksTotal + " passed)";
            }
            return "AWS status checks did not become available in time; current state: " + status;
        }
    }

    /**
     * A VM that reached RUNNING is a successful start even if its status checks didn't all
     * report OK within the poll timeout (those routinely take 2-5+ min, well past the point the
     * instance is actually up and usable) — only a VM that never reached RUNNING at all is a
     * real failure. Without this, {@code startVm} would report success only when {@code ready()}
     * is true, forcing every caller to fall back on a second live {@code getVmStatus} call
     * (see {@code VmOperationsService.reconcileCloudStateAfterFailure}) just to reach the same
     * conclusion.
     */
    private VmOperationResult toStartResult(String requestId, StartReadiness readiness, String providerVmId) {
        if (readiness.ready()) {
            log.info("AWS EC2 instance {} reached RUNNING state and passed all reported status checks", providerVmId);
            return VmOperationResult.success(requestId, VmStatus.RUNNING);
        }
        if (readiness.status() == VmStatus.RUNNING) {
            log.warn("AWS EC2 instance {} reached RUNNING but status checks did not complete in time ({}/{} passed) — treating start as successful",
                    providerVmId, readiness.checksPassed(), readiness.checksTotal());
            return VmOperationResult.success(requestId, VmStatus.RUNNING);
        }
        if (readiness.status() == VmStatus.STOPPED || readiness.status() == VmStatus.TERMINATED) {
            log.warn("AWS EC2 instance {} returned to {} while starting", providerVmId, readiness.status());
            return VmOperationResult.failure("Instance returned to " + readiness.status() + " while starting");
        }
        log.warn("AWS EC2 instance {} did not reach RUNNING in time, current status: {}", providerVmId, readiness.status());
        return VmOperationResult.failure(readiness.timeoutMessage());
    }

    /**
     * Wait for EC2 instance to reach STOPPED state.
     * Polls every 10 seconds, times out after 3 minutes. Reports progress each tick so a
     * cancelled execution (an {@link OperationCancelledException} thrown by the listener) is
     * noticed within one poll interval instead of only after this method returns.
     */
    /** A stop succeeded only if the instance is STOPPED; still STOPPING (or anything else) is a timeout. */
    private VmOperationResult stopResult(String requestId, VmStatus finalStatus) {
        if (finalStatus == VmStatus.STOPPED) {
            return VmOperationResult.success(requestId, VmStatus.STOPPED);
        }
        return VmOperationResult.timedOut(
                "Instance still " + finalStatus + " after " + (stopTimeoutMs / 1000) + "s", finalStatus);
    }

    private VmStatus waitForStopped(Ec2Client ec2, String instanceId, OperationProgressListener progressListener) {
        long startTime = System.currentTimeMillis();
        VmStatus lastKnownStatus = VmStatus.STOPPING;

        while (System.currentTimeMillis() - startTime < stopTimeoutMs) {
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

                notifyProgress(progressListener, VmOperationProgress.of(VmStatus.STOPPING, "EC2 stopping", 50));

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Stop polling interrupted for instance {}", instanceId);
                return lastKnownStatus;
            } catch (OperationCancelledException e) {
                throw e;
            } catch (Exception e) {
                log.warn("Error polling state for instance {}: {}", instanceId, e.getMessage());
            }
        }

        log.warn("Timed out waiting for instance {} to stop after {}ms", instanceId, stopTimeoutMs);
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
     *
     * @throws com.tcgdigital.vmcontrol.exception.DiscoveryFailedException if the EC2 call fails
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
            // An empty list would read as "every instance is gone" (H24).
            throw new com.tcgdigital.vmcontrol.exception.DiscoveryFailedException("EC2 discovery failed in " + region, e);
        }
    }

    /**
     * Discovers all non-terminated EC2 instances in a region that carry the given tag key/value.
     * Uses the SDK paginator so results beyond the 1000-item page limit are not missed.
     *
     * @throws com.tcgdigital.vmcontrol.exception.DiscoveryFailedException if the EC2 call fails
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
            // An empty list would read as "every instance is gone" (H24).
            throw new com.tcgdigital.vmcontrol.exception.DiscoveryFailedException("EC2 discovery failed in " + region, e);
        }
    }

    /**
     * Applies (or overwrites) the given tags on the specified EC2 instances — used for
     * cost-allocation tagging (tcg:managed-by/environment/team), never called during normal
     * start/stop/discovery. Batches at 20 resources per CreateTags call, the actual EC2 API limit.
     */
    public void tagInstances(String region, List<String> instanceIds, Map<String, String> tags) {
        if (instanceIds.isEmpty() || tags.isEmpty()) {
            return;
        }
        Ec2Client ec2 = getEc2Client(region);
        List<Tag> ec2Tags = tags.entrySet().stream()
                .map(e -> Tag.builder().key(e.getKey()).value(e.getValue()).build())
                .toList();

        int chunkSize = 20;
        for (int i = 0; i < instanceIds.size(); i += chunkSize) {
            List<String> chunk = instanceIds.subList(i, Math.min(i + chunkSize, instanceIds.size()));
            ec2.createTags(CreateTagsRequest.builder()
                    .resources(chunk)
                    .tags(ec2Tags)
                    .build());
        }
        log.info("Tagged {} EC2 instance(s) in region {} with {}", instanceIds.size(), region, tags);
    }

    /**
     * Changes an EC2 instance's type via {@code ModifyInstanceAttribute} — AWS only accepts this
     * call while the instance is stopped (rejects it with {@code IncorrectInstanceState}
     * otherwise); the caller is expected to have already verified the VM is stopped before
     * calling this, this is defense-in-depth, not the only guard. Not part of the
     * {@link CloudProviderService} interface — same as {@link #tagInstances}, there's no other
     * real cloud-provider implementation this needs to stay compatible with.
     */
    public void changeInstanceType(String region, String providerVmId, String newInstanceType) {
        Ec2Client ec2 = getEc2Client(region);
        ec2.modifyInstanceAttribute(ModifyInstanceAttributeRequest.builder()
                .instanceId(providerVmId)
                .instanceType(AttributeValue.builder().value(newInstanceType).build())
                .build());
        log.info("Changed EC2 instance {} in region {} to instance type {}", providerVmId, region, newInstanceType);
    }

    /** The instance's current type as AWS reports it, or null if it cannot be read. */
    public String getInstanceType(String region, String providerVmId) {
        DescribeInstancesResponse response = getEc2Client(region).describeInstances(
                DescribeInstancesRequest.builder().instanceIds(providerVmId).build());
        if (!response.reservations().isEmpty() && !response.reservations().get(0).instances().isEmpty()) {
            return response.reservations().get(0).instances().get(0).instanceTypeAsString();
        }
        return null;
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

