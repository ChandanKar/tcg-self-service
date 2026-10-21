package com.tcgdigital.vmcontrol.dto;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

public record DashboardSummaryDTO(
        String persona,
        SummaryDTO summary,
        UtilizationDTO utilization,
        StorageDTO storage,
        CoverageDTO coverage,
        List<EnvironmentCardDTO> environments,
        List<ChartPointDTO> utilizationTrend,
        Map<String, Integer> vmStatusCounts,
        Map<String, Integer> regionCounts,
        Map<String, Integer> providerCounts,
        Map<String, Integer> volumeTypeCounts,
        List<TopVmDTO> topBusyVms,
        List<TopVmDTO> idleVms,
        List<SchedulerHealthDTO> schedulerHealth,
        List<RiskComplianceDTO> riskCompliance,
        List<RecommendationDTO> recommendations,
        Timestamp generatedAt,
        /** What needs the viewer soon, earliest first (U06-T01): leases ending within 3 h, grants about to end. */
        List<NeedsYouDTO> needsYou
) {
    public record SummaryDTO(
            Integer environments,
            Integer totalVms,
            Integer runningVms,
            Integer stoppedVms,
            Integer idleVms,
            Integer driftedVms,
            Integer lockedEnvironments,
            Integer pendingAccessRequests,
            /** Month to date across the viewer's environments ("$X this month"); for administrators across all (U14-T01). */
            BigDecimal monthToDateCost
    ) {}

    public record UtilizationDTO(
            BigDecimal avgCpuUtilization,
            Long totalNetworkInBytes,
            Long totalNetworkOutBytes,
            Long totalDiskReadBytes,
            Long totalDiskWriteBytes,
            Timestamp latestMetricSampleTime
    ) {}

    public record StorageDTO(
            Integer totalAllocatedStorageGib,
            Integer volumeCount,
            Integer usedStorageGib,
            Integer freeStorageGib
    ) {}

    public record CoverageDTO(
            Integer inventoryPercent,
            Integer metricsPercent,
            Integer idleSummaryPercent
    ) {}

    public record EnvironmentCardDTO(
            String environmentId,
            String name,
            String displayName,
            String serviceType,
            Integer totalVms,
            Integer runningVms,
            Integer stoppedVms,
            Integer idleVms,
            BigDecimal avgCpuUtilization,
            Integer allocatedStorageGib,
            Boolean locked,
            // Home (U06-T01); administrators get it too (U14-T01, the two largest environments this month)
            BigDecimal monthToDateCost,
            /** ADMIN, USER or VIEWER; null for a group-only grant. */
            String myAccess,
            String lockedByName,
            Boolean lockedByMe,
            Timestamp lockExpiresAt,
            java.time.Instant leaseEndsAt,
            java.time.Instant nextStart,
            java.time.Instant nextStop,
            /** Starting or stopping (of which startingVms are starting), and failed (error, not found, terminated). */
            Integer transitionalVms,
            Integer startingVms,
            Integer failedVms,
            /** VM state counts per group in start order, for the power strip (U05-T03). */
            List<GroupStateCountsDTO> groupStates
    ) {}

    public record ChartPointDTO(
            String label,
            BigDecimal cpuUtilization,
            BigDecimal memoryUtilization,
            Long networkInBytes,
            Long networkOutBytes,
            Long diskReadBytes,
            Long diskWriteBytes
    ) {}

    public record TopVmDTO(
            String vmId,
            String name,
            String environmentName,
            String status,
            BigDecimal cpuUtilization,
            Integer idleDurationMinutes,
            Integer allocatedStorageGib,
            String environmentId,
            String groupName,
            /** On-demand price per hour from the pricing reference; null when unknown. */
            BigDecimal hourlyRate
    ) {}

    /**
     * Something the viewer should act on soon (U06-T01). kind LEASE_ENDING (referenceId = lease id)
     * or ACCESS_ENDING (referenceId = access id); at = when it ends.
     */
    public record NeedsYouDTO(
            String kind,
            String environmentId,
            String environmentName,
            java.time.Instant at,
            String referenceId
    ) {}

    /** {@code owner} is shown to admins only; {@code expectedIntervalSeconds} is the job's own schedule (E12-T05). */
    public record SchedulerHealthDTO(
            String name,
            String status,
            Timestamp lastRunAt,
            Timestamp lockedUntil,
            Long freshnessSeconds,
            String owner,
            Long expectedIntervalSeconds
    ) {}

    public record RiskComplianceDTO(
            String tone,
            String title,
            String description,
            Integer count
    ) {}

    public record RecommendationDTO(
            String tone,
            String title,
            String description,
            Integer count
    ) {}
}
