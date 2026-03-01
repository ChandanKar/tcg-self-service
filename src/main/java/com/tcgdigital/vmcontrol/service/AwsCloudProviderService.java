package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.VmStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;

import java.util.concurrent.CompletableFuture;

/**
 * AWS EC2 implementation of CloudProviderService.
 */
@Service
public class AwsCloudProviderService implements CloudProviderService {

    private static final Logger log = LoggerFactory.getLogger(AwsCloudProviderService.class);

    private static final int STATUS_CHECK_POLL_INTERVAL_MS = 10_000; // 10 seconds
    private static final int STATUS_CHECK_TIMEOUT_MS = 300_000;      // 5 minutes
    private static final int STOP_POLL_INTERVAL_MS = 10_000;         // 10 seconds
    private static final int STOP_TIMEOUT_MS = 180_000;              // 3 minutes

    @Value("${aws.access-key:}")
    private String accessKey;

    @Value("${aws.secret-key:}")
    private String secretKey;

    @Value("${aws.region:us-east-1}")
    private String defaultRegion;

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

                if (!response.startingInstances().isEmpty()) {
                    InstanceStateChange stateChange = response.startingInstances().get(0);
                    log.info("Started AWS EC2 instance {}: {} -> {}",
                            providerVmId,
                            stateChange.previousState().nameAsString(),
                            stateChange.currentState().nameAsString());

                    // Wait for instance to pass 2/2 status checks
                    VmStatus finalStatus = waitForStatusChecks(ec2, providerVmId);

                    if (finalStatus == VmStatus.RUNNING) {
                        log.info("AWS EC2 instance {} passed 2/2 status checks — fully operational", providerVmId);
                        return VmOperationResult.success(
                                response.responseMetadata().requestId(),
                                VmStatus.RUNNING
                        );
                    } else {
                        log.warn("AWS EC2 instance {} did not pass status checks in time, current status: {}", providerVmId, finalStatus);
                        return VmOperationResult.success(
                                response.responseMetadata().requestId(),
                                finalStatus
                        );
                    }
                }

                return VmOperationResult.failure("No instance state change returned");

            } catch (Ec2Exception e) {
                log.error("Failed to start AWS EC2 instance {}: {}", providerVmId, e.getMessage());
                return VmOperationResult.failure("AWS Error: " + e.awsErrorDetails().errorMessage());
            } catch (Exception e) {
                log.error("Failed to start AWS EC2 instance {}: {}", providerVmId, e.getMessage());
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

                if (!response.stoppingInstances().isEmpty()) {
                    InstanceStateChange stateChange = response.stoppingInstances().get(0);
                    log.info("Stopped AWS EC2 instance {}: {} -> {}",
                            providerVmId,
                            stateChange.previousState().nameAsString(),
                            stateChange.currentState().nameAsString());

                    // Wait for instance to fully stop
                    VmStatus finalStatus = waitForStopped(ec2, providerVmId);

                    log.info("AWS EC2 instance {} stop complete, final status: {}", providerVmId, finalStatus);
                    return VmOperationResult.success(
                            response.responseMetadata().requestId(),
                            finalStatus
                    );
                }

                return VmOperationResult.failure("No instance state change returned");

            } catch (Ec2Exception e) {
                log.error("Failed to stop AWS EC2 instance {}: {}", providerVmId, e.getMessage());
                return VmOperationResult.failure("AWS Error: " + e.awsErrorDetails().errorMessage());
            } catch (Exception e) {
                log.error("Failed to stop AWS EC2 instance {}: {}", providerVmId, e.getMessage());
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
                Thread.sleep(STATUS_CHECK_POLL_INTERVAL_MS);

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
                Thread.sleep(STOP_POLL_INTERVAL_MS);

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

            return VmStatus.UNKNOWN;

        } catch (Exception e) {
            log.error("Failed to get status for AWS EC2 instance {}: {}", providerVmId, e.getMessage());
            return VmStatus.UNKNOWN;
        }
    }

    @Override
    public boolean isAvailable() {
        return accessKey != null && !accessKey.isEmpty()
                && secretKey != null && !secretKey.isEmpty();
    }

    private Ec2Client getEc2Client(String region) {
        String effectiveRegion = region != null && !region.isEmpty() ? region : defaultRegion;

        return Ec2Client.builder()
                .region(Region.of(effectiveRegion))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .build();
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
            case TERMINATED -> VmStatus.STOPPED;
            default -> VmStatus.UNKNOWN;
        };
    }
}

