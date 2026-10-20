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
        List<RecommendationDTO> recommendations,
        /** "24h" or "7d": the window of the CPU averages and the busiest VMs (U08-T08). */
        String window,
        /** What to do, most useful first (U08-T08): stop an idle VM, resize one, stop on weekends too. */
        List<ActionDTO> actions
) {
    /**
     * One thing to do (U08-T08). kind STOP_IDLE (vmId, hourlyRate: what it costs an hour while it
     * idles), RESIZE (vmId, recommendedType, monthlySavings) or SCHEDULE_WEEKENDS (no VM; detail says
     * how many VMs idled how long over the weekend).
     */
    public record ActionDTO(
            String kind,
            String vmId,
            String vmName,
            String groupName,
            String recommendedType,
            BigDecimal hourlyRate,
            BigDecimal monthlySavings,
            String detail
    ) {}

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
            String groupId,
            String groupName,
            String status,
            BigDecimal cpuUtilization,
            Integer idleDurationMinutes,
            Integer allocatedStorageGib,
            Timestamp latestSampleTime,
            /** Average CPU over the insights window (U08-T08); null without samples in it. */
            BigDecimal avgCpuWindow
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
