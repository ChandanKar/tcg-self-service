package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.Objects;

/**
 * One day of an EKS workload's cost from Split Cost Allocation Data (E26-T04): the split cost its
 * pods were charged and the unused node capacity attributed to it.
 */
@Entity
@Table(name = "eks_workload_cost_daily")
@IdClass(EksWorkloadCostDaily.Key.class)
public class EksWorkloadCostDaily {

    public static class Key implements Serializable {
        private String environmentId;
        private Date usageDate;
        private String namespace;
        private String workload;

        public Key() {
        }

        public Key(String environmentId, Date usageDate, String namespace, String workload) {
            this.environmentId = environmentId;
            this.usageDate = usageDate;
            this.namespace = namespace;
            this.workload = workload;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(environmentId, k.environmentId) && Objects.equals(usageDate, k.usageDate)
                    && Objects.equals(namespace, k.namespace) && Objects.equals(workload, k.workload);
        }

        @Override
        public int hashCode() {
            return Objects.hash(environmentId, usageDate, namespace, workload);
        }
    }

    @Id
    @Column(name = "environment_id", length = 36)
    private String environmentId;

    @Id
    @Column(name = "usage_date")
    private Date usageDate;

    @Id
    @Column(name = "namespace", length = 253)
    private String namespace;

    @Id
    @Column(name = "workload", length = 253)
    private String workload;

    @Column(name = "split_cost", nullable = false, precision = 14, scale = 4)
    private BigDecimal splitCost = BigDecimal.ZERO;

    @Column(name = "unused_cost", nullable = false, precision = 14, scale = 4)
    private BigDecimal unusedCost = BigDecimal.ZERO;

    @Column(name = "ingested_at", nullable = false)
    private Timestamp ingestedAt;

    public EksWorkloadCostDaily() {
    }

    public EksWorkloadCostDaily(String environmentId, Date usageDate, String namespace, String workload,
                                BigDecimal splitCost, BigDecimal unusedCost, Timestamp ingestedAt) {
        this.environmentId = environmentId;
        this.usageDate = usageDate;
        this.namespace = namespace;
        this.workload = workload;
        this.splitCost = splitCost;
        this.unusedCost = unusedCost;
        this.ingestedAt = ingestedAt;
    }

    public String getEnvironmentId() {
        return environmentId;
    }

    public Date getUsageDate() {
        return usageDate;
    }

    public String getNamespace() {
        return namespace;
    }

    public String getWorkload() {
        return workload;
    }

    public BigDecimal getSplitCost() {
        return splitCost;
    }

    public BigDecimal getUnusedCost() {
        return unusedCost;
    }

    public Timestamp getIngestedAt() {
        return ingestedAt;
    }
}
