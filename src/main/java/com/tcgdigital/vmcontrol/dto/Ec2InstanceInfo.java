package com.tcgdigital.vmcontrol.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Schema(description = "EC2 Instance Information")
public record Ec2InstanceInfo(
    @Schema(description = "EC2 Instance ID", example = "i-0123456789abcdef0")
    String instanceId,

    @Schema(description = "Instance type", example = "t2.micro")
    String instanceType,

    @Schema(description = "Instance state", example = "running")
    String state,

    @Schema(description = "Private IP address", example = "10.0.1.100")
    String privateIpAddress,

    @Schema(description = "Public IP address", example = "54.123.45.67")
    String publicIpAddress,

    @Schema(description = "Private DNS name")
    String privateDnsName,

    @Schema(description = "Public DNS name")
    String publicDnsName,

    @Schema(description = "VPC ID", example = "vpc-0123456789abcdef0")
    String vpcId,

    @Schema(description = "Subnet ID", example = "subnet-0123456789abcdef0")
    String subnetId,

    @Schema(description = "Availability Zone", example = "us-east-1a")
    String availabilityZone,

    @Schema(description = "AMI Image ID", example = "ami-0123456789abcdef0")
    String imageId,

    @Schema(description = "Key pair name", example = "my-key-pair")
    String keyName,

    @Schema(description = "Launch time")
    Instant launchTime,

    @Schema(description = "Platform (e.g., Windows)", example = "windows")
    String platform,

    @Schema(description = "Architecture", example = "x86_64")
    String architecture,

    @Schema(description = "Root device type", example = "ebs")
    String rootDeviceType,

    @Schema(description = "Root device name", example = "/dev/xvda")
    String rootDeviceName,

    @Schema(description = "Virtualization type", example = "hvm")
    String virtualizationType,

    @Schema(description = "Hypervisor", example = "xen")
    String hypervisor,

    @Schema(description = "IAM instance profile ARN")
    String iamInstanceProfileArn,

    @Schema(description = "Security group IDs")
    List<String> securityGroupIds,

    @Schema(description = "Security group names")
    List<String> securityGroupNames,

    @Schema(description = "Tags as key-value pairs")
    Map<String, String> tags,

    @Schema(description = "EBS optimized flag")
    Boolean ebsOptimized,

    @Schema(description = "Monitoring state", example = "disabled")
    String monitoringState,

    @Schema(description = "Tenancy", example = "default")
    String tenancy,

    @Schema(description = "Core count")
    Integer coreCount,

    @Schema(description = "Threads per core")
    Integer threadsPerCore,

    @Schema(description = "AWS Region", example = "us-east-1")
    String region
) {}

