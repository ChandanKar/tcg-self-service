package com.tcgdigital.vmcontrol.model;

/**
 * What an access grant (or access request) applies to.
 *
 * <p>{@code GROUP} covers both an EC2 VM group and an EKS node group — a node group is
 * modelled as a {@link VmGroup}, so one scope type serves both.
 */
public enum AccessScopeType {
    ENVIRONMENT,
    GROUP
}
