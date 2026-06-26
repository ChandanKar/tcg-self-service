package com.tcgdigital.vmcontrol.dto;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

public record EnvironmentInsightsDTO(
        String environmentId,
        String name,
        String displayName,
        String serviceType,
        Integer groupCount,
        Integer totalVms,
        Integer runningVms,
        Integer stoppedVms,
        Integer transitionalVms,
        Integer unknownVms,
        Integer driftedVms,
        Integer idleVms,
        Integer activeVms,
        Integer missingInventoryVms,
        Integer missingMetricVms,
        Integer totalAllocatedStorageGib,
        Integer volumeCount,
        BigDecimal avgCpuUtilization,
        Long totalNetworkInBytes,
        Long totalNetworkOutBytes,
        Long totalDiskReadBytes,
        Long totalDiskWriteBytes,
        List<VmMetricSeriesDTO> cpuSeries,
        List<VmMetricSeriesDTO> networkInSeries,
        List<VmMetricSeriesDTO> networkOutSeries,
        List<VmMetricSeriesDTO> diskReadSeries,
        List<VmMetricSeriesDTO> diskWriteSeries,
        Timestamp latestMetricSampleTime,
        Timestamp latestInventoryRefreshTime,
        Map<String, Integer> statusCounts,
        Map<String, Integer> regionCounts,
        Map<String, Integer> volumeTypeCounts,
        List<GroupInsightDTO> groups,
        List<VmInsightRowDTO> busiestVms,
        List<VmInsightRowDTO> idleVmRows,
        List<RecommendationDTO> recommendations
) {
    public record GroupInsightDTO(
            String groupId,
            String name,
            Integer sequencePosition,
            Integer totalVms,
            Integer runningVms,
            Integer stoppedVms,
            Integer idleVms,
            BigDecimal avgCpuUtilization
    ) {}

    public record VmInsightRowDTO(
            String vmId,
            String name,
            String groupName,
            String status,
            BigDecimal cpuUtilization,
            Integer idleDurationMinutes,
            Integer allocatedStorageGib,
            Timestamp latestSampleTime
    ) {}

    public record VmMetricSeriesDTO(
            String vmId,
            String name,
            List<Integer> values
    ) {}

    public record RecommendationDTO(
            String tone,
            String title,
            String description,
            Integer count
    ) {}
}
