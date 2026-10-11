package com.tcgdigital.vmcontrol.dto;

/**
 * An ACTIVE member account of the AWS organization (E28-T08), with the role ARN the platform
 * proposes for it (the configured member role name) and whether it is registered already.
 */
public record OrganizationAccountCandidateDTO(
        String accountNumber,
        String name,
        String email,
        String proposedRoleArn,
        boolean alreadyRegistered
) {}
