package com.tcgdigital.vmcontrol.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Create or update an AWS account (E28-T07). The role ARN is required except on the default
 * account; on update a blank external ID keeps the current one.
 */
public record UpsertAwsAccountDTO(
        @NotBlank @Size(max = 255) String displayName,
        @Pattern(regexp = "^arn:aws(-[a-z]+)?:iam::\\d{12}:role/[\\w+=,.@/-]+$", message = "must be an IAM role ARN")
        String roleArn,
        @Size(min = 2, max = 1224) String externalId,
        List<@Pattern(regexp = "^[a-z]{2}(-[a-z]+)+-\\d$", message = "must be an AWS region such as eu-west-1") String> regions,
        Boolean isBillingAccount
) {}
