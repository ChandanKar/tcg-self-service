package com.tcgdigital.vmcontrol.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "EC2 Instance Action Response")
public record Ec2InstanceActionResponse(
    @Schema(description = "EC2 Instance ID", example = "i-0123456789abcdef0")
    String instanceId,

    @Schema(description = "Action performed (start, stop)", example = "start")
    String action,

    @Schema(description = "Previous state before action", example = "stopped")
    String previousState,

    @Schema(description = "Current state after action", example = "pending")
    String currentState,

    @Schema(description = "Whether the action was successful")
    boolean success,

    @Schema(description = "Message describing the result")
    String message,

    @Schema(description = "AWS Region", example = "ap-south-1")
    String region
) {}

