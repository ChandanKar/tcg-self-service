package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.Ec2InstanceActionResponse;
import com.tcgdigital.vmcontrol.dto.Ec2InstanceInfo;
import com.tcgdigital.vmcontrol.dto.Ec2InstanceStatus;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class Ec2Service {

    private final AwsCredentialsProvider awsCredentialsProvider;

    public Ec2Service(AwsCredentialsProvider awsCredentialsProvider) {
        this.awsCredentialsProvider = awsCredentialsProvider;
    }

    /**
     * Creates an EC2 client for the specified region.
     */
    private Ec2Client createEc2Client(String region) {
        return Ec2Client.builder()
                .region(Region.of(region))
                .credentialsProvider(awsCredentialsProvider)
                .build();
    }

    /**
     * Starts an EC2 instance and waits for it to be running with status checks passed.
     *
     * @param instanceId The EC2 instance ID
     * @param region     AWS region
     * @return Action response with status
     */
    public Ec2InstanceActionResponse startInstance(String instanceId, String region) {
        try (Ec2Client ec2Client = createEc2Client(region)) {
            // First, check current state
            Ec2InstanceStatus currentStatus = getInstanceStatus(instanceId, region);

            if ("running".equalsIgnoreCase(currentStatus.instanceState())) {
                // Check if status checks are passed
                if ("ok".equalsIgnoreCase(currentStatus.systemStatus()) &&
                    "ok".equalsIgnoreCase(currentStatus.instanceStatus())) {
                    return new Ec2InstanceActionResponse(
                            instanceId,
                            "start",
                            currentStatus.instanceState(),
                            currentStatus.instanceState(),
                            true,
                            "Instance is already running and status checks are passed",
                            region
                    );
                } else {
                    return new Ec2InstanceActionResponse(
                            instanceId,
                            "start",
                            currentStatus.instanceState(),
                            currentStatus.instanceState(),
                            false,
                            "Instance is running but status checks are not passed. System: " +
                                currentStatus.systemStatus() + ", Instance: " + currentStatus.instanceStatus(),
                            region
                    );
                }
            }

            // Start the instance
            StartInstancesRequest startRequest = StartInstancesRequest.builder()
                    .instanceIds(instanceId)
                    .build();

            StartInstancesResponse startResponse = ec2Client.startInstances(startRequest);

            if (startResponse.startingInstances().isEmpty()) {
                return new Ec2InstanceActionResponse(
                        instanceId,
                        "start",
                        currentStatus.instanceState(),
                        currentStatus.instanceState(),
                        false,
                        "Failed to start instance",
                        region
                );
            }

            InstanceStateChange stateChange = startResponse.startingInstances().get(0);
            String previousState = stateChange.previousState() != null ?
                    stateChange.previousState().nameAsString() : "unknown";
            String newState = stateChange.currentState() != null ?
                    stateChange.currentState().nameAsString() : "pending";

            // Wait for instance to be running (with timeout)
            boolean isRunning = waitForInstanceState(ec2Client, instanceId, "running", 120);

            if (!isRunning) {
                return new Ec2InstanceActionResponse(
                        instanceId,
                        "start",
                        previousState,
                        newState,
                        false,
                        "Instance start initiated but timed out waiting for running state",
                        region
                );
            }

            // Wait for status checks to pass
            boolean statusChecksPassed = waitForStatusChecks(ec2Client, instanceId, 180);

            Ec2InstanceStatus finalStatus = getInstanceStatus(instanceId, region);

            if (statusChecksPassed) {
                return new Ec2InstanceActionResponse(
                        instanceId,
                        "start",
                        previousState,
                        finalStatus.instanceState(),
                        true,
                        "Instance started successfully and status checks passed",
                        region
                );
            } else {
                return new Ec2InstanceActionResponse(
                        instanceId,
                        "start",
                        previousState,
                        finalStatus.instanceState(),
                        false,
                        "Instance started but status checks did not pass within timeout. System: " +
                            finalStatus.systemStatus() + ", Instance: " + finalStatus.instanceStatus(),
                        region
                );
            }
        }
    }

    /**
     * Stops an EC2 instance.
     *
     * @param instanceId The EC2 instance ID
     * @param region     AWS region
     * @return Action response with status
     */
    public Ec2InstanceActionResponse stopInstance(String instanceId, String region) {
        try (Ec2Client ec2Client = createEc2Client(region)) {
            // First, check current state
            Ec2InstanceStatus currentStatus = getInstanceStatus(instanceId, region);

            if ("stopped".equalsIgnoreCase(currentStatus.instanceState())) {
                return new Ec2InstanceActionResponse(
                        instanceId,
                        "stop",
                        currentStatus.instanceState(),
                        currentStatus.instanceState(),
                        true,
                        "Instance is already stopped",
                        region
                );
            }

            // Stop the instance
            StopInstancesRequest stopRequest = StopInstancesRequest.builder()
                    .instanceIds(instanceId)
                    .build();

            StopInstancesResponse stopResponse = ec2Client.stopInstances(stopRequest);

            if (stopResponse.stoppingInstances().isEmpty()) {
                return new Ec2InstanceActionResponse(
                        instanceId,
                        "stop",
                        currentStatus.instanceState(),
                        currentStatus.instanceState(),
                        false,
                        "Failed to stop instance",
                        region
                );
            }

            InstanceStateChange stateChange = stopResponse.stoppingInstances().get(0);
            String previousState = stateChange.previousState() != null ?
                    stateChange.previousState().nameAsString() : "unknown";
            String newState = stateChange.currentState() != null ?
                    stateChange.currentState().nameAsString() : "stopping";

            // Wait for instance to be stopped (with timeout)
            boolean isStopped = waitForInstanceState(ec2Client, instanceId, "stopped", 120);

            Ec2InstanceStatus finalStatus = getInstanceStatus(instanceId, region);

            return new Ec2InstanceActionResponse(
                    instanceId,
                    "stop",
                    previousState,
                    finalStatus.instanceState(),
                    isStopped,
                    isStopped ? "Instance stopped successfully" : "Instance stop initiated but timed out waiting for stopped state",
                    region
            );
        }
    }

    /**
     * Gets the current status of an EC2 instance.
     *
     * @param instanceId The EC2 instance ID
     * @param region     AWS region
     * @return Instance status information
     */
    public Ec2InstanceStatus getInstanceStatus(String instanceId, String region) {
        try (Ec2Client ec2Client = createEc2Client(region)) {
            // Get instance state
            DescribeInstancesRequest describeRequest = DescribeInstancesRequest.builder()
                    .instanceIds(instanceId)
                    .build();

            DescribeInstancesResponse describeResponse = ec2Client.describeInstances(describeRequest);

            String instanceState = "unknown";
            String availabilityZone = null;

            for (Reservation reservation : describeResponse.reservations()) {
                for (Instance instance : reservation.instances()) {
                    if (instance.instanceId().equals(instanceId)) {
                        instanceState = instance.state() != null ? instance.state().nameAsString() : "unknown";
                        availabilityZone = instance.placement() != null ? instance.placement().availabilityZone() : null;
                        break;
                    }
                }
            }

            // Get status checks
            String systemStatus = "not-applicable";
            String instanceStatus = "not-applicable";

            try {
                DescribeInstanceStatusRequest statusRequest = DescribeInstanceStatusRequest.builder()
                        .instanceIds(instanceId)
                        .includeAllInstances(true)
                        .build();

                DescribeInstanceStatusResponse statusResponse = ec2Client.describeInstanceStatus(statusRequest);

                for (InstanceStatus status : statusResponse.instanceStatuses()) {
                    if (status.instanceId().equals(instanceId)) {
                        if (status.systemStatus() != null && status.systemStatus().status() != null) {
                            systemStatus = status.systemStatus().statusAsString();
                        }
                        if (status.instanceStatus() != null && status.instanceStatus().status() != null) {
                            instanceStatus = status.instanceStatus().statusAsString();
                        }
                        break;
                    }
                }
            } catch (Exception e) {
                // Status checks may not be available for all instance states
            }

            return new Ec2InstanceStatus(
                    instanceId,
                    instanceState,
                    systemStatus,
                    instanceStatus,
                    availabilityZone,
                    region
            );
        }
    }

    /**
     * Waits for an instance to reach a specific state.
     */
    private boolean waitForInstanceState(Ec2Client ec2Client, String instanceId, String targetState, int timeoutSeconds) {
        long startTime = System.currentTimeMillis();
        long timeoutMillis = timeoutSeconds * 1000L;

        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            try {
                DescribeInstancesRequest request = DescribeInstancesRequest.builder()
                        .instanceIds(instanceId)
                        .build();

                DescribeInstancesResponse response = ec2Client.describeInstances(request);

                for (Reservation reservation : response.reservations()) {
                    for (Instance instance : reservation.instances()) {
                        if (instance.instanceId().equals(instanceId)) {
                            String currentState = instance.state() != null ? instance.state().nameAsString() : "";
                            if (targetState.equalsIgnoreCase(currentState)) {
                                return true;
                            }
                        }
                    }
                }

                Thread.sleep(5000); // Poll every 5 seconds
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * Waits for status checks to pass.
     */
    private boolean waitForStatusChecks(Ec2Client ec2Client, String instanceId, int timeoutSeconds) {
        long startTime = System.currentTimeMillis();
        long timeoutMillis = timeoutSeconds * 1000L;

        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            try {
                DescribeInstanceStatusRequest statusRequest = DescribeInstanceStatusRequest.builder()
                        .instanceIds(instanceId)
                        .build();

                DescribeInstanceStatusResponse statusResponse = ec2Client.describeInstanceStatus(statusRequest);

                for (InstanceStatus status : statusResponse.instanceStatuses()) {
                    if (status.instanceId().equals(instanceId)) {
                        boolean systemOk = status.systemStatus() != null &&
                                "ok".equalsIgnoreCase(status.systemStatus().statusAsString());
                        boolean instanceOk = status.instanceStatus() != null &&
                                "ok".equalsIgnoreCase(status.instanceStatus().statusAsString());

                        if (systemOk && instanceOk) {
                            return true;
                        }
                    }
                }

                Thread.sleep(10000); // Poll every 10 seconds
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * Lists all EC2 instances in the specified region.
     *
     * @param region AWS region (e.g., "us-east-1", "eu-west-1")
     * @return List of EC2 instance information
     */
    public List<Ec2InstanceInfo> listInstances(String region) {
        try (Ec2Client ec2Client = createEc2Client(region)) {

            List<Ec2InstanceInfo> instances = new ArrayList<>();
            DescribeInstancesRequest request = DescribeInstancesRequest.builder().build();

            DescribeInstancesResponse response;
            do {
                response = ec2Client.describeInstances(request);

                for (Reservation reservation : response.reservations()) {
                    for (Instance instance : reservation.instances()) {
                        instances.add(mapToEc2InstanceInfo(instance, region));
                    }
                }

                request = DescribeInstancesRequest.builder()
                        .nextToken(response.nextToken())
                        .build();

            } while (response.nextToken() != null);

            return instances;
        }
    }

    private Ec2InstanceInfo mapToEc2InstanceInfo(Instance instance, String region) {
        // Extract security group IDs and names
        List<String> securityGroupIds = instance.securityGroups().stream()
                .map(GroupIdentifier::groupId)
                .collect(Collectors.toList());

        List<String> securityGroupNames = instance.securityGroups().stream()
                .map(GroupIdentifier::groupName)
                .collect(Collectors.toList());

        // Extract tags as a map
        Map<String, String> tags = new HashMap<>();
        if (instance.tags() != null) {
            for (Tag tag : instance.tags()) {
                tags.put(tag.key(), tag.value());
            }
        }

        // Extract IAM instance profile ARN
        String iamInstanceProfileArn = null;
        if (instance.iamInstanceProfile() != null) {
            iamInstanceProfileArn = instance.iamInstanceProfile().arn();
        }

        // Extract CPU options
        Integer coreCount = null;
        Integer threadsPerCore = null;
        if (instance.cpuOptions() != null) {
            coreCount = instance.cpuOptions().coreCount();
            threadsPerCore = instance.cpuOptions().threadsPerCore();
        }

        // Extract monitoring state
        String monitoringState = null;
        if (instance.monitoring() != null) {
            monitoringState = instance.monitoring().stateAsString();
        }

        // Extract placement tenancy
        String tenancy = null;
        if (instance.placement() != null) {
            tenancy = instance.placement().tenancyAsString();
        }

        return new Ec2InstanceInfo(
                instance.instanceId(),
                instance.instanceTypeAsString(),
                instance.state() != null ? instance.state().nameAsString() : null,
                instance.privateIpAddress(),
                instance.publicIpAddress(),
                instance.privateDnsName(),
                instance.publicDnsName(),
                instance.vpcId(),
                instance.subnetId(),
                instance.placement() != null ? instance.placement().availabilityZone() : null,
                instance.imageId(),
                instance.keyName(),
                instance.launchTime(),
                instance.platformAsString(),
                instance.architectureAsString(),
                instance.rootDeviceTypeAsString(),
                instance.rootDeviceName(),
                instance.virtualizationTypeAsString(),
                instance.hypervisorAsString(),
                iamInstanceProfileArn,
                securityGroupIds,
                securityGroupNames,
                tags,
                instance.ebsOptimized(),
                monitoringState,
                tenancy,
                coreCount,
                threadsPerCore,
                region
        );
    }
}

