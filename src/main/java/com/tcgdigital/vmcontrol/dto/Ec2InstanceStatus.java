package com.tcgdigital.vmcontrol.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "EC2 Instance Status Information")
public record Ec2InstanceStatus(
    @Schema(description = "EC2 Instance ID", example = "i-0123456789abcdef0")
    String instanceId,

    @Schema(description = "Instance state (pending, running, stopping, stopped, shutting-down, terminated)", example = "running")
    String instanceState,

    @Schema(description = "System status check (ok, impaired, initializing, insufficient-data, not-applicable)", example = "ok")
    String systemStatus,

    @Schema(description = "Instance status check (ok, impaired, initializing, insufficient-data, not-applicable)", example = "ok")
    String instanceStatus,

    @Schema(description = "Availability Zone", example = "ap-south-1a")
    String availabilityZone,

    @Schema(description = "AWS Region", example = "ap-south-1")
    String region
) {}

