package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.model.VmIdleSummary;
import com.tcgdigital.vmcontrol.model.VmMetricSample;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;

public class VmMetricsDTO {
    private String vmId;
    private String window;
    private Integer periodSeconds;
    private IdleDTO idle;
    private SampleDTO latest;
    private List<SampleDTO> series;

    public static VmMetricsDTO from(String vmId, String window, Integer periodSeconds,
                                    VmIdleSummary idleSummary, List<VmMetricSample> samples) {
        VmMetricsDTO dto = new VmMetricsDTO();
        dto.setVmId(vmId);
        dto.setWindow(window);
        dto.setPeriodSeconds(periodSeconds);
        dto.setIdle(IdleDTO.from(idleSummary));
        dto.setSeries(samples.stream().map(SampleDTO::from).toList());
        dto.setLatest(samples.isEmpty() ? null : SampleDTO.from(samples.get(samples.size() - 1)));
        return dto;
    }

    public static class IdleDTO {
        private Boolean idle;
        private Timestamp idleSince;
        private Integer idleDurationMinutes;
        private String reason;

        public static IdleDTO from(VmIdleSummary summary) {
            IdleDTO dto = new IdleDTO();
            if (summary != null) {
                dto.setIdle(summary.getIdle());
                dto.setIdleSince(summary.getIdleSince());
                dto.setIdleDurationMinutes(summary.getIdleDurationMinutes());
                dto.setReason(summary.getReason());
            } else {
                dto.setIdle(false);
                dto.setIdleDurationMinutes(0);
                dto.setReason("No metrics collected yet");
            }
            return dto;
        }

        public Boolean getIdle() { return idle; }
        public void setIdle(Boolean idle) { this.idle = idle; }
        public Timestamp getIdleSince() { return idleSince; }
        public void setIdleSince(Timestamp idleSince) { this.idleSince = idleSince; }
        public Integer getIdleDurationMinutes() { return idleDurationMinutes; }
        public void setIdleDurationMinutes(Integer idleDurationMinutes) { this.idleDurationMinutes = idleDurationMinutes; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }

    public static class SampleDTO {
        private Timestamp sampleTime;
        private BigDecimal cpuUtilization;
        private Long networkInBytes;
        private Long networkOutBytes;
        private Long diskReadBytes;
        private Long diskWriteBytes;

        public static SampleDTO from(VmMetricSample sample) {
            SampleDTO dto = new SampleDTO();
            dto.setSampleTime(sample.getSampleTime());
            dto.setCpuUtilization(sample.getCpuUtilization());
            dto.setNetworkInBytes(sample.getNetworkInBytes());
            dto.setNetworkOutBytes(sample.getNetworkOutBytes());
            dto.setDiskReadBytes(sample.getDiskReadBytes());
            dto.setDiskWriteBytes(sample.getDiskWriteBytes());
            return dto;
        }

        public Timestamp getSampleTime() { return sampleTime; }
        public void setSampleTime(Timestamp sampleTime) { this.sampleTime = sampleTime; }
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

    public String getVmId() { return vmId; }
    public void setVmId(String vmId) { this.vmId = vmId; }
    public String getWindow() { return window; }
    public void setWindow(String window) { this.window = window; }
    public Integer getPeriodSeconds() { return periodSeconds; }
    public void setPeriodSeconds(Integer periodSeconds) { this.periodSeconds = periodSeconds; }
    public IdleDTO getIdle() { return idle; }
    public void setIdle(IdleDTO idle) { this.idle = idle; }
    public SampleDTO getLatest() { return latest; }
    public void setLatest(SampleDTO latest) { this.latest = latest; }
    public List<SampleDTO> getSeries() { return series; }
    public void setSeries(List<SampleDTO> series) { this.series = series; }
}
