package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.model.VmIdleSummary;
import com.tcgdigital.vmcontrol.model.VmInventorySnapshot;

import java.math.BigDecimal;
import java.sql.Timestamp;

public class VmUtilizationSummaryDTO {
    private String vmId;
    private String instanceType;
    private Integer totalStorageGib;
    private Boolean idle;
    private Integer idleDurationMinutes;
    private BigDecimal latestCpuUtilization;
    private Timestamp latestSampleTime;

    public static VmUtilizationSummaryDTO from(String vmId, VmInventorySnapshot inventory, VmIdleSummary idleSummary) {
        VmUtilizationSummaryDTO dto = new VmUtilizationSummaryDTO();
        dto.setVmId(vmId);
        if (inventory != null) {
            dto.setInstanceType(inventory.getInstanceType());
            dto.setTotalStorageGib(inventory.getTotalStorageGib());
        }
        if (idleSummary != null) {
            dto.setIdle(idleSummary.getIdle());
            dto.setIdleDurationMinutes(idleSummary.getIdleDurationMinutes());
            dto.setLatestCpuUtilization(idleSummary.getLatestCpuUtilization());
            dto.setLatestSampleTime(idleSummary.getLatestSampleTime());
        }
        return dto;
    }

    public String getVmId() { return vmId; }
    public void setVmId(String vmId) { this.vmId = vmId; }
    public String getInstanceType() { return instanceType; }
    public void setInstanceType(String instanceType) { this.instanceType = instanceType; }
    public Integer getTotalStorageGib() { return totalStorageGib; }
    public void setTotalStorageGib(Integer totalStorageGib) { this.totalStorageGib = totalStorageGib; }
    public Boolean getIdle() { return idle; }
    public void setIdle(Boolean idle) { this.idle = idle; }
    public Integer getIdleDurationMinutes() { return idleDurationMinutes; }
    public void setIdleDurationMinutes(Integer idleDurationMinutes) { this.idleDurationMinutes = idleDurationMinutes; }
    public BigDecimal getLatestCpuUtilization() { return latestCpuUtilization; }
    public void setLatestCpuUtilization(BigDecimal latestCpuUtilization) { this.latestCpuUtilization = latestCpuUtilization; }
    public Timestamp getLatestSampleTime() { return latestSampleTime; }
    public void setLatestSampleTime(Timestamp latestSampleTime) { this.latestSampleTime = latestSampleTime; }
}
