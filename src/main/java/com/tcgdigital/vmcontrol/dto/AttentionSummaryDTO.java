package com.tcgdigital.vmcontrol.dto;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;

/**
 * What needs an administrator's attention across the platform, in one call (U05-T04, Admin Home's
 * "Needs attention" grid and health line). Counts and facts only; the screen writes the sentences.
 * Every block is present, with zero counts when there is nothing to do.
 */
public record AttentionSummaryDTO(
        Waiting accessRequests,
        Waiting approvals,
        FailedOperations failedOperations,
        VmsToReview vmsToReview,
        Budgets budgets,
        LateJobs lateJobs,
        Health health,
        /** The month's plan for the spend chart; null when no budget is enabled (the chart then has no plan line). */
        MonthPlan monthPlan,
        /** Active EKS environments with no known AWS region (U19-T04): they are not synced until it is set. */
        RegionsUnknown regionsUnknown,
        Timestamp generatedAt) {

    /** Requests waiting for a decision: how many, and since when the oldest has waited. */
    public record Waiting(long count, Timestamp oldestAt) { }

    /** Failed or partly failed runs in the last 24 hours, runs that stopped reporting, and where (up to 3 names). */
    public record FailedOperations(long count24h, long stuck, List<String> environmentNames) { }

    public record VmsToReview(long drift, long pendingReview) { }

    /**
     * Enabled budgets at 90 % or more of their amount, or forecast past it; top is the one most at risk,
     * by the larger of spent and forecast percentages (U23-T10).
     */
    public record Budgets(long nearLimit, BudgetUse top) { }

    /** forecastPercent: the month-end forecast against the amount (U23-T10); null without a forecast. */
    public record BudgetUse(String scopeName, BigDecimal percentUsed, BigDecimal forecastPercent) { }

    /** Scheduled jobs that are late, stale or never ran (switched-off jobs excluded); worst is the latest one. */
    public record LateJobs(long count, LateJob worst) { }

    public record LateJob(String label, Long lateBySeconds) { }

    /** jobsOnTime counts healthy and running jobs among the enabled ones. */
    public record Health(long jobsEnabled, long jobsOnTime, Timestamp lastStateSyncAt, long driftEvents24h) { }

    /** Enabled team budgets plus enabled environment budgets outside those teams (nothing counted twice). */
    public record MonthPlan(BigDecimal amount, long budgetCount) { }

    /** How many, up to 3 names, and the first one's id (its settings are where the region is set). */
    public record RegionsUnknown(long count, List<String> environmentNames, String firstEnvironmentId) { }
}
