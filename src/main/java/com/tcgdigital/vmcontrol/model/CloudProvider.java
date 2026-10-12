package com.tcgdigital.vmcontrol.model;

/**
 * Cloud provider types supported by the platform.
 */
public enum CloudProvider {
    AWS,
    AZURE,
    GCP,
    OCI,
    AWS_EKS,
    /** RDS DB instances and Aurora clusters (E33-T01): providerVmId db:&lt;id&gt; or cluster:&lt;id&gt;. */
    AWS_RDS;

    /** Providers reached through an AWS account (E28): they carry an awsAccountRef. */
    public boolean isAws() {
        return this == AWS || this == AWS_EKS || this == AWS_RDS;
    }
}

