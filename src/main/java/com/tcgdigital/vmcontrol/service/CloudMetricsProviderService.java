package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.CloudProvider;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface CloudMetricsProviderService {

    CloudProvider getProvider();

    boolean isAvailable();

    Map<String, VmMetricData> fetchLatestMetrics(List<String> providerVmIds, String region,
                                                 Instant start, Instant end, int periodSeconds);

    class VmMetricData {
        private String providerVmId;
        private Timestamp sampleTime;
        private Integer periodSeconds;
        private BigDecimal cpuUtilization;
        private Long networkInBytes;
        private Long networkOutBytes;
        private Long diskReadBytes;
        private Long diskWriteBytes;

        public String getProviderVmId() { return providerVmId; }
        public void setProviderVmId(String providerVmId) { this.providerVmId = providerVmId; }
        public Timestamp getSampleTime() { return sampleTime; }
        public void setSampleTime(Timestamp sampleTime) { this.sampleTime = sampleTime; }
        public Integer getPeriodSeconds() { return periodSeconds; }
        public void setPeriodSeconds(Integer periodSeconds) { this.periodSeconds = periodSeconds; }
        public BigDecimal getCpuUtilization() { return cpuUtilization; }
        public void setCpuUtilization(BigDecimal cpuUtilization) { this.cpuUtilization = cpuUtilization; }
        public Long getNetworkInBytes() { return networkInBytes; }
        public void setNetworkInBytes(Long networkInBytes) { this.networkInBytes = networkInBytes; }
        public Long getNetworkOutBytes() { return networkOutBytes; }
        public void setNetworkOutBytes(Long networkOutBytes) { this.networkOutBytes = networkOutBytes; }
        public Long getDiskReadBytes() { return diskReadBytes; }
        public void setDiskReadBytes(Long diskReadBytes) { this.diskReadBytes = diskReadBytes; }
        public Long getDiskWriteBytes() { return diskWriteBytes; }
        public void setDiskWriteBytes(Long diskWriteBytes) { this.diskWriteBytes = diskWriteBytes; }
    }
}
