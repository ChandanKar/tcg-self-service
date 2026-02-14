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

                    return VmOperationResult.success(
                            response.responseMetadata().requestId(),
                            mapAwsStateToVmStatus(stateChange.currentState().name())
                    );
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

                    return VmOperationResult.success(
                            response.responseMetadata().requestId(),
                            mapAwsStateToVmStatus(stateChange.currentState().name())
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

