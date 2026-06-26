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
        Timestamp generatedAt
) {
    public record SummaryDTO(
            Integer environments,
            Integer totalVms,
            Integer runningVms,
            Integer stoppedVms,
            Integer idleVms,
            Integer driftedVms,
            Integer lockedEnvironments,
            Integer pendingAccessRequests
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
            Boolean locked
    ) {}

    public record ChartPointDTO(
            String label,
            BigDecimal cpuUtilization,
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
            Integer allocatedStorageGib
    ) {}

    public record SchedulerHealthDTO(
            String name,
            String status,
            Timestamp lastRunAt,
            Timestamp lockedUntil,
            Long freshnessSeconds,
            String owner
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
