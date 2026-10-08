package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.Ec2InstanceInfo;
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

